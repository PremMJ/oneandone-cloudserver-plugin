package org.jenkinsci.plugins.oneandonecloudserver;

import hudson.Extension;
import hudson.model.Descriptor;
import hudson.model.TaskListener;
import hudson.slaves.AbstractCloudComputer;
import hudson.slaves.AbstractCloudSlave;
import hudson.slaves.NodeProperty;
import hudson.slaves.RetentionStrategy;
import jenkins.model.Jenkins;

import java.io.IOException;
import java.util.List;
import java.util.logging.Logger;

public class Slave extends AbstractCloudSlave {

    private static final Logger LOG = Logger.getLogger(Slave.class.getName());

    private final String cloudName;
    private final int idleTerminationTime;
    private final String initScript;
    private final String serverId;
    private final String privateKey;
    private final String remoteAdmin;
    private final String jvmOpts;
    private final long startTimeMillis;
    private final int sshPort;

    public Slave(String cloudName, String name, String nodeDescription, String serverId, String privateKey,
                 String remoteAdmin, String remoteFS, int sshPort, int numExecutors, int idleTerminationTime, Mode mode,
                 String labelString, ComputerLauncher launcher, RetentionStrategy retentionStrategy,
                 List<? extends NodeProperty<?>> nodeProperties, String initScript, String jvmOpts)
            throws Descriptor.FormException, IOException {

        super(name, nodeDescription, remoteFS, numExecutors, mode, labelString, launcher, retentionStrategy, nodeProperties);
        this.cloudName = cloudName;
        this.serverId = serverId;
        this.privateKey = privateKey;
        this.idleTerminationTime = idleTerminationTime;
        this.initScript = initScript;
        this.remoteAdmin = remoteAdmin;
        this.jvmOpts = jvmOpts;
        this.sshPort = sshPort;

        startTimeMillis = System.currentTimeMillis();
    }

    @Extension
    public static class DescriptorImpl extends SlaveDescriptor {

        @Override
        public String getDisplayName() {
            return "1&1 Slave";
        }

        @Override
        public boolean isInstantiable() {
            return false;
        }
    }

    /**
     * Override to create a ProfitBricks {@link org.jenkinsci.plugins.oneandonecloudserver.Computer}
     * @return a new Computer instance, instantiated with this Slave instance.
     */
    @Override
    public Computer createComputer() {
        return new Computer(this);
    }

    /**
     * Retrieve a handle to the associated {@link org.jenkinsci.plugins.oneandonecloudserver.Cloud}
     * @return the Cloud associated with the specified cloudName
     */
    public Cloud getCloud() {
        return (Cloud) Jenkins.getInstance().getCloud(cloudName);
    }

    /**
     * Get the name of the remote admin user
     * @return the remote admin user, defaulting to "root"
     */
    public String getRemoteAdmin() {
        if (remoteAdmin == null || remoteAdmin.length() == 0)
            return "root";
        return remoteAdmin;
    }

    /**
     * Performs the removal of the underlying resource from the cloud.
     */
    @Override
    protected void _terminate(TaskListener listener) throws IOException, InterruptedException {
        OneAndOne.tryDestroyServerAsync(getCloud().getApiToken(), serverId);
    }

    public long getStartTimeMillis() {
        return startTimeMillis;
    }

    public String getServerId() {
        return serverId;
    }

    public String getPrivateKey() {
        return privateKey;
    }

    public int getIdleTerminationTime() {
        return idleTerminationTime;
    }

    public String getInitScript() {
        return initScript;
    }

    public String getJvmOpts() {
        return jvmOpts;
    }

    public int getSshPort() {
        return sshPort;
    }

}
