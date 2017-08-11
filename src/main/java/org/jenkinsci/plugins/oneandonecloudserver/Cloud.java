package org.jenkinsci.plugins.oneandonecloudserver;

import com.google.common.base.Strings;
import com.oneandone.rest.POJO.Response.ServerResponse;
import com.oneandone.rest.POJO.Response.Types;
import com.oneandone.sdk.OneAndOneApi;
import hudson.Extension;
import hudson.model.Computer;
import hudson.model.Descriptor;
import hudson.model.Label;
import hudson.model.Node;
import hudson.slaves.NodeProvisioner;
import hudson.util.FormValidation;
import hudson.util.ListBoxModel;
import jenkins.model.Jenkins;
import org.kohsuke.stapler.DataBoundConstructor;
import org.kohsuke.stapler.QueryParameter;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.StringReader;
import java.util.ArrayList;
import java.util.List;
import java.util.Collection;
import java.util.Collections;
import java.util.concurrent.Callable;
import java.util.logging.Level;
import java.util.logging.Logger;

public class Cloud extends hudson.slaves.Cloud {

    private static final Logger LOGGER = Logger.getLogger(Cloud.class.getName());

    /**
     * Sometimes nodes can be provisioned very fast (or in parallel), leading to more nodes being
     * provisioned than the instance cap allows, as they all check Profitbricks at about the same time
     * right before provisioning and see that instance cap was not reached yet. So, for example, there
     * might be a situation where 2 nodes see that 1 more node can be provisioned before the instance cap
     * is reached, and they both happily provision, making one more node being provisioned than the instance
     * cap allows. Thus we need a synchronization, so that only one node at a time could be provisioned, to
     * remove the race condition.
     */
    private static final Object provisionSynchronizor = new Object();

    /**
     * The 1&1 API auth token
     * @see "https://cloudpanel-api.1and1.com/documentation/v1/en/documentation.html"
     */
    private final String apiToken;

    /**
     * The SSH key to be added to the new server.
     */
    private final String sshKey;

    /**
     * The SSH private key associated with the selected SSH key
     */
    private final String privateKey;

    private final Integer instanceCap;
    private final Integer timeoutMinutes;

    /**
     * List of {@link org.jenkinsci.plugins.oneandonecloudserver.SlaveTemplate}
     */
    private final List<? extends SlaveTemplate> templates;

    /**
     * Constructor parameters are injected via jelly in the jenkins global configuration
     * @param name A name associated with this cloud configuration
     * @param apiToken 1&amp;1 Cloud Server API authentication token
     * @param sshKey public ssh key
     * @param privateKey private ssh key
     * @param instanceCap The maximum number of instances that can be started
     * @param timeoutMinutes timeout in minutes
     * @param templates The templates for this cloud
     */
    @DataBoundConstructor
    public Cloud(String name,
                 String apiToken,
                 String sshKey,
                 String privateKey,
                 String instanceCap,
                 String timeoutMinutes,
                 List<? extends SlaveTemplate> templates) {
        super(name);

        LOGGER.log(Level.INFO, "Constructing new Cloud(name = {0}, instanceCap = {1}, ...)", new Object[]{name, instanceCap});

        this.apiToken = apiToken;
        this.sshKey = sshKey;
        this.privateKey = privateKey;
        this.instanceCap = Integer.parseInt(instanceCap);
        this.timeoutMinutes = timeoutMinutes == null || timeoutMinutes.isEmpty() ? 10 : Integer.parseInt(timeoutMinutes);

        if (templates == null) {
            this.templates = Collections.emptyList();
        } else {
            this.templates = templates;
        }

        LOGGER.info("Creating 1&1 cloud with " + this.templates.size() + " templates");
    }

    public boolean isInstanceCapReachedLocal() {
        if (instanceCap == 0) {
            return false;
        }

        int count = 0;

        LOGGER.log(Level.INFO, "cloud limit check");

        List<Node> nodes = Jenkins.getInstance().getNodes();
        for (Node n : nodes) {
            if (ServerName.isServerInstanceOfCloud(n.getDisplayName(), name)) {
                count ++;
            }
        }

        return count >= Math.min(instanceCap, getSlaveInstanceCap());
    }

    public boolean isInstanceCapReachedRemote(List<ServerResponse> servers) {

        int count = 0;

        LOGGER.log(Level.INFO, "cloud limit check");

        for (ServerResponse server : servers) {
            if (!server.getStatus().getState().equals(Types.ServerState.REMOVING)) {
                if (ServerName.isServerInstanceOfCloud(server.getName(), name)) {
                    count ++;
                }
            }
        }

        return count >= Math.min(instanceCap, getSlaveInstanceCap());
    }

    private int getSlaveInstanceCap() {
        int slaveTotalInstanceCap = 0;
        for (SlaveTemplate t : templates) {
            int slaveInstanceCap = t.getInstanceCap();
            if (slaveInstanceCap == 0) {
                slaveTotalInstanceCap = Integer.MAX_VALUE;
                break;
            } else {
                slaveTotalInstanceCap += t.getInstanceCap();
            }
        }

        return slaveTotalInstanceCap;
    }

    /**
     * The actual logic for provisioning a new server when it's needed by Jenkins.
     */
    @Override
    public Collection<NodeProvisioner.PlannedNode> provision(Label label, int excessWorkload) {
        synchronized (provisionSynchronizor) {
            List<NodeProvisioner.PlannedNode> provisioningNodes = new ArrayList<NodeProvisioner.PlannedNode>();
            try {
                while (excessWorkload > 0) {
                    List<ServerResponse> servers = OneAndOne.getServers(apiToken);

                    if (isInstanceCapReachedLocal() || isInstanceCapReachedRemote(servers)) {
                        LOGGER.log(Level.INFO, "Instance cap reached, not provisioning.");
                        break;
                    }

                    final SlaveTemplate template = getTemplateBelowInstanceCap(servers, label);
                    if (template == null) {
                        break;
                    }

                    final String serverName = ServerName.generateServerName(name, template.getName());

                    provisioningNodes.add(new NodeProvisioner.PlannedNode(serverName, Computer.threadPoolForRemoting.submit(new Callable<Node>() {
                        public Node call() throws Exception {
                            Slave slave;
                            synchronized (provisionSynchronizor) {
                                List<ServerResponse> servers = OneAndOne.getServers(apiToken);

                                if (isInstanceCapReachedLocal() || isInstanceCapReachedRemote(servers)) {
                                    LOGGER.log(Level.INFO, "Instance cap reached, not provisioning.");
                                    return null;
                                }
                                slave = template.provision(serverName, name, apiToken, privateKey, sshKey, servers);
                            }
                            Jenkins.getInstance().addNode(slave);
                            slave.toComputer().connect(false).get();
                            return slave;
                        }
                    }), template.getNumExecutors()));

                    excessWorkload -= template.getNumExecutors();

                }

                LOGGER.info("Provisioning " + provisioningNodes.size() + " 1&1 nodes");

                return provisioningNodes;
            } catch (Exception e) {
                LOGGER.log(Level.WARNING, e.getMessage(), e);
                return Collections.emptyList();
            }
        }
    }

    /**
     * Returns true if this cloud is capable of provisioning new nodes for the given label.
     */
    @Override
    public boolean canProvision(Label label) {
        synchronized (provisionSynchronizor) {
            try {
                SlaveTemplate template = getTemplateBelowInstanceCapLocal(label);
                if (template == null) {
                    LOGGER.log(Level.INFO, "No slaves could provision for label " + label.getDisplayName() + " because they either didn't support such a label or have reached the instance cap.");
                    return false;
                }

                if (isInstanceCapReachedLocal()) {
                    LOGGER.log(Level.INFO, "Instance cap of " + getInstanceCap() + " reached, not provisioning for label " + label.getDisplayName() + ".");
                    return false;
                }
            } catch (Exception e) {
                LOGGER.log(Level.WARNING, e.getMessage(), e);
            }

            return true;
        }
    }

    public List<SlaveTemplate> getTemplates(Label label) {
        List<SlaveTemplate> matchingTemplates = new ArrayList<SlaveTemplate>();

        for (SlaveTemplate t : templates) {
            if ((label == null && t.getLabelSet().size() == 0) ||
                    (label == null && t.isLabellessJobsAllowed()) ||
                    (label != null && label.matches(t.getLabelSet()))) {
                matchingTemplates.add(t);
            }
        }

        return matchingTemplates;
    }

    public SlaveTemplate getTemplateBelowInstanceCap(List<ServerResponse> servers, Label label) {
        List<SlaveTemplate> matchingTempaltes = getTemplates(label);

        try {
            for (SlaveTemplate t : matchingTempaltes) {
                if (!t.isInstanceCapReachedLocal(name) && !t.isInstanceCapReachedRemote(servers, name)) {
                    return t;
                }
            }
        } catch (Exception e) {
            LOGGER.log(Level.WARNING, e.getMessage(), e);
        }

        return null;
    }

    public SlaveTemplate getTemplateBelowInstanceCapLocal(Label label) {
        List<SlaveTemplate> matchingTempaltes = getTemplates(label);

        try {
            for (SlaveTemplate t : matchingTempaltes) {
                if (!t.isInstanceCapReachedLocal(name)) {
                    return t;
                }
            }
        } catch (Exception e) {
            LOGGER.log(Level.WARNING, e.getMessage(), e);
        }

        return null;
    }

    public String getName() {
        return name;
    }

    public String getApiToken() {
        return apiToken;
    }

    public String getPrivateKey() {
        return privateKey;
    }

    public int getInstanceCap() {
        return instanceCap;
    }

    public OneAndOneApi getApiClient() {
        OneAndOneApi apiClient = new OneAndOneApi();
        apiClient.setToken(apiToken);

        return apiClient;
    }

    public List<SlaveTemplate> getTemplates() {
        return Collections.unmodifiableList(templates);
    }

    public Integer getTimeoutMinutes() {
        return timeoutMinutes;
    }

    @Extension
    public static final class DescriptorImpl extends Descriptor<hudson.slaves.Cloud> {

        public DescriptorImpl() {
            load();
        }

        public String getDisplayName() {
            return "1&1";
        }

        public FormValidation doTestConnection(@QueryParameter String apiToken) {
            try {
                OneAndOneApi apiClient = new OneAndOneApi();
                apiClient.setToken(apiToken);
                apiClient.getDataCenterApi().getDataCenters(0, 0, null, null, null);
                return FormValidation.ok("1&1 API request succeeded.");
            } catch (Exception e) {
                LOGGER.log(Level.WARNING, "Failed to connect to 1&1 API", e);
                return FormValidation.error(e.getMessage());
            }
        }

        public FormValidation doCheckName(@QueryParameter String name) {
            if (Strings.isNullOrEmpty(name)) {
                return FormValidation.error("Must be set");
            } else if (!ServerName.isValidCloudName(name)) {
                return FormValidation.error("Must consist of A-Z, a-z, 0-9 and . symbols");
            } else {
                return FormValidation.ok();
            }
        }

        public static FormValidation doCheckApiToken(@QueryParameter String apiToken) {
            if (Strings.isNullOrEmpty(apiToken)) {
                return FormValidation.error("API token must be set");
            } else {
                return FormValidation.ok();
            }
        }

        public FormValidation doCheckPrivateKey(@QueryParameter String value) throws IOException {
            boolean hasStart=false,hasEnd=false;
            BufferedReader br = new BufferedReader(new StringReader(value));
            String line;
            while ((line = br.readLine()) != null) {
                if (line.equals("-----BEGIN RSA PRIVATE KEY-----"))
                    hasStart=true;
                if (line.equals("-----END RSA PRIVATE KEY-----"))
                    hasEnd=true;
            }
            if(!hasStart)
                return FormValidation.error("This doesn't look like a private key");
            if(!hasEnd)
                return FormValidation.error("The private key is missing the trailing 'END RSA PRIVATE KEY' marker. Copy&paste error?");
            return FormValidation.ok();
        }

        public FormValidation doCheckInstanceCap(@QueryParameter String instanceCap) {
            if (Strings.isNullOrEmpty(instanceCap)) {
                return FormValidation.error("Instance cap must be set");
            } else {
                int instanceCapNumber;

                try {
                    instanceCapNumber = Integer.parseInt(instanceCap);
                } catch (Exception e) {
                    return FormValidation.error("Instance cap must be a number");
                }

                if (instanceCapNumber < 0) {
                    return FormValidation.error("Instance cap must be a positive number");
                }

                return FormValidation.ok();
            }
        }
    }
}
