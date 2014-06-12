package hudson.plugins.ec2;

import com.amazonaws.services.ec2.AmazonEC2;
import com.amazonaws.services.ec2.model.*;
import hudson.Extension;
import hudson.model.AsyncPeriodicWork;
import hudson.model.TaskListener;
import hudson.model.Node;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.logging.Level;
import java.util.logging.Logger;

import jenkins.model.Jenkins;

import com.amazonaws.AmazonClientException;

/**
 * @author Bruno Meneguello
 */
@Extension
public class EC2SlaveMonitor extends AsyncPeriodicWork {

    private Long recurrencePeriod;

    public EC2SlaveMonitor() {
        super("EC2 alive slaves monitor");
        recurrencePeriod = Long.getLong("jenkins.ec2.checkAlivePeriod", TimeUnit.MINUTES.toMillis(10));
        LOGGER.log(Level.FINE, "EC2 check alive period is {0}ms", recurrencePeriod);
    }

    @Override
    public long getRecurrencePeriod() {
        return recurrencePeriod;
    }

    @Override
    protected void execute(TaskListener listener) throws IOException, InterruptedException {
        AmazonEC2Cloud cloud = (AmazonEC2Cloud)Jenkins.getInstance().getCloud("EC2Cloud");
        AmazonEC2 ec2 = cloud.connect();
        List<EC2AbstractSlave> nodes = filterEC2Nodes(Jenkins.getInstance().getNodes());
        List<String> deathRow = new ArrayList<String>();
        for (EC2AbstractSlave node : nodes) {
            try {
                if (!node.isAlive(true)) {
                    LOGGER.info("EC2 instance is dead: " + node.getInstanceId());
                    node.terminate();
                }
            } catch (AmazonClientException e) {
                LOGGER.info("EC2 instance is dead and failed to terminate: " + node.getInstanceId());
                removeNode(node);
            }
        }

        for(SlaveTemplate template : cloud.getTemplates()){
            DescribeInstancesRequest describeInstancesRequest = new DescribeInstancesRequest().withFilters(new Filter("image-id", Arrays.asList(template.ami)));
            DescribeInstancesResult describeInstancesResult = ec2.describeInstances(describeInstancesRequest);
            for(Reservation reservation : describeInstancesResult.getReservations()) {
                for(Instance instance : reservation.getInstances()) {
                    if(!nodesContainInstance(instance.getInstanceId(), nodes)) {
                        deathRow.add(instance.getInstanceId());
                    }
                }
            }

        }

        TerminateInstancesRequest request = new TerminateInstancesRequest(deathRow);
        ec2.terminateInstances(request);

    }

    private List<EC2AbstractSlave> filterEC2Nodes(List<Node> nodes) {
        ArrayList<EC2AbstractSlave> ec2AbstractSlaves = new ArrayList<EC2AbstractSlave>();
        for(Node node : nodes) {
            if (node instanceof EC2AbstractSlave) {
                ec2AbstractSlaves.add((EC2AbstractSlave) node);
            }
        }
        return ec2AbstractSlaves;
    }

    private boolean nodesContainInstance(String instanceId, List<EC2AbstractSlave> nodes) {
        for(Node node : nodes) {
            if(node.getNodeName().contains(instanceId)) return true;
        }
        return false;
    }

    private void removeNode(EC2AbstractSlave ec2Slave) {
        try {
            Jenkins.getInstance().removeNode(ec2Slave);
        } catch (IOException e) {
            LOGGER.log(Level.WARNING, "Failed to remove node: " + ec2Slave.getInstanceId());
        }
    }

    private static final Logger LOGGER = Logger.getLogger(EC2SlaveMonitor.class.getName());

}
