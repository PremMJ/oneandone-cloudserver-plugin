package org.jenkinsci.plugins.oneandonecloudserver;

import com.google.common.base.Strings;
import com.oneandone.rest.POJO.Requests.CreateServerRequest;
import com.oneandone.rest.POJO.Requests.HardwareRequest;
import com.oneandone.rest.POJO.Response.AvailableHardwareFlavour;
import com.oneandone.rest.POJO.Response.ServerAppliancesResponse;
import com.oneandone.rest.POJO.Response.ServerResponse;
import com.oneandone.rest.POJO.Response.Types;
import com.oneandone.sdk.OneAndOneApi;
import hudson.Extension;
import hudson.RelativePath;
import hudson.Util;
import hudson.model.Describable;
import hudson.model.Descriptor;
import hudson.model.Label;
import hudson.model.Node;
import hudson.model.labels.LabelAtom;
import hudson.slaves.NodeProperty;
import hudson.util.FormValidation;
import hudson.util.ListBoxModel;
import jenkins.model.Jenkins;
import org.kohsuke.stapler.DataBoundConstructor;
import org.kohsuke.stapler.QueryParameter;

import java.io.IOException;
import java.util.*;
import java.util.logging.Level;
import java.util.logging.Logger;

import static com.google.common.collect.Lists.newArrayList;

public class SlaveTemplate implements Describable<SlaveTemplate> {

    private static final Logger LOGGER = Logger.getLogger(SlaveTemplate.class.getName());

    private final String name;
    private final String fixedInstanceSizeId;
    private final String applianceId;

    private final int idleTerminationInMinutes;
    private final int numExecutors;
    private final Integer instanceCap;
    private final String labelString;
    private final String labels;
    private final Boolean labellessJobsAllowed;
    private transient Set<LabelAtom> labelSet;

    /**
     * Setup script for preparing the new slave.
     */
    private final String initScript;
    private final String username;
    private final String workspacePath;
    private final Integer sshPort;

    @DataBoundConstructor
    public SlaveTemplate(String name, String applianceId, String fixedInstanceSizeId, String username, String workspacePath,
                         Integer sshPort, String idleTerminationInMinutes, String numExecutors, String labelString,
                         Boolean labellessJobsAllowed, String instanceCap, String initScript) {
        this.name = name;
        this.applianceId = applianceId;
        this.fixedInstanceSizeId = fixedInstanceSizeId;
        this.username = username;
        this.workspacePath = workspacePath;
        this.sshPort = sshPort;

        this.idleTerminationInMinutes = tryParseInteger(idleTerminationInMinutes, 10);
        this.numExecutors = tryParseInteger(numExecutors, 1);
        this.labelString = labelString;
        this.labellessJobsAllowed = labellessJobsAllowed;
        this.labels = Util.fixNull(labelString);
        this.instanceCap = Integer.parseInt(instanceCap);

        this.initScript = initScript;

        readResolve();
    }

    public boolean isInstanceCapReachedLocal(String cloudName) {
        if (instanceCap == 0) {
            return false;
        }
        LOGGER.log(Level.INFO, "slave limit check");

        int count = 0;
        List<Node> nodes = Jenkins.getInstance().getNodes();
        for (Node n : nodes) {
            if (ServerName.isServerInstanceOfSlave(n.getDisplayName(), cloudName, name)) {
                count++;
            }
        }

        return count >= instanceCap;
    }

    public boolean isInstanceCapReachedRemote(List<ServerResponse> servers, String cloudName) {
        LOGGER.log(Level.INFO, "slave limit check");
        int count = 0;
        for (ServerResponse server : servers) {
            if (!server.getStatus().getState().equals(Types.ServerState.REMOVING)) {
                if (ServerName.isServerInstanceOfSlave(server.getName(), cloudName, name)) {
                    count++;
                }
            }
        }

        return count >= instanceCap;
    }

    public Slave provision(String serverName, String cloudName, String apiToken, String privateKey, String sshKey, List<ServerResponse> servers) {

        LOGGER.log(Level.INFO, "Provisioning slave...");

        try {
            LOGGER.log(Level.INFO, "Starting to provision 1&1 server using image: " + applianceId +
                    ", fixedInstanceSizeId: " + fixedInstanceSizeId);

            if (isInstanceCapReachedLocal(cloudName) || isInstanceCapReachedRemote(servers, cloudName)) {
                throw new AssertionError();
            }

            // create a new server
            CreateServerRequest server = new CreateServerRequest();
            server.setName(serverName);
            server.setRsaKey(sshKey);

            HardwareRequest hardwareRequest = new HardwareRequest();
            hardwareRequest.setFixedInstanceSizeId(fixedInstanceSizeId);

            server.setHardware(hardwareRequest);
            server.setApplianceId(applianceId);

            LOGGER.log(Level.INFO, "Creating slave with new server " + serverName);

            OneAndOneApi apiClient = new OneAndOneApi();
            apiClient.setToken(apiToken);
            ServerResponse createdServer = apiClient.getServerApi().createServer(server);

            return newSlave(cloudName, createdServer, privateKey);
        } catch (Exception e) {
            LOGGER.log(Level.WARNING, e.getMessage(), e);
            throw new AssertionError();
        }
    }

    /**
     * Create a new {@link Slave} from the given {@link ServerResponse}
     * @param server the server being created
     * @return the provisioned {@link Slave}
     * @throws IOException
     * @throws Descriptor.FormException
     */
    private Slave newSlave(String cloudName, ServerResponse server, String privateKey) throws IOException, Descriptor.FormException {
        LOGGER.log(Level.INFO, "Creating new slave...");
        return new Slave(
                cloudName,
                server.getName(),
                "Computer running on 1&1 with name: " + server.getName(),
                server.getId(),
                privateKey,
                username,
                workspacePath,
                sshPort,
                numExecutors,
                idleTerminationInMinutes,
                Node.Mode.NORMAL,
                labels,
                new ComputerLauncher(),
                new RetentionStrategy(),
                Collections.<NodeProperty<?>>emptyList(),
                Util.fixNull(initScript),
                ""
        );
    }

    @Extension
    public static final class DescriptorImpl extends Descriptor<SlaveTemplate> {

        @Override
        public String getDisplayName() {
            return null;
        }

        public FormValidation doCheckName(@QueryParameter String name) {
            if (Strings.isNullOrEmpty(name)) {
                return FormValidation.error("Must be set");
            } else if (!ServerName.isValidSlaveName(name)) {
                return FormValidation.error("Must consist of A-Z, a-z, 0-9 and . symbols");
            } else {
                return FormValidation.ok();
            }
        }

        public FormValidation doCheckUsername(@QueryParameter String username) {
            if (Strings.isNullOrEmpty(username)) {
                return FormValidation.error("Must be set");
            } else {
                return FormValidation.ok();
            }
        }

        public FormValidation doCheckWorkspacePath(@QueryParameter String workspacePath) {
            if (Strings.isNullOrEmpty(workspacePath)) {
                return FormValidation.error("Must be set");
            } else {
                return FormValidation.ok();
            }
        }

        private static FormValidation doCheckNonNegativeNumber(String stringNumber) {
            if (Strings.isNullOrEmpty(stringNumber)) {
                return FormValidation.error("Must be set");
            } else {
                int number;

                try {
                    number = Integer.parseInt(stringNumber);
                } catch (Exception e) {
                    return FormValidation.error("Must be a number");
                }

                if (number < 0) {
                    return FormValidation.error("Must be a nonnegative number");
                }

                return FormValidation.ok();
            }
        }

        public FormValidation doCheckSshPort(@QueryParameter String sshPort) {
            return doCheckNonNegativeNumber(sshPort);
        }

        public FormValidation doCheckNumExecutors(@QueryParameter String numExecutors) {
            if (Strings.isNullOrEmpty(numExecutors)) {
                return FormValidation.error("Must be set");
            } else {
                int number;

                try {
                    number = Integer.parseInt(numExecutors);
                } catch (Exception e) {
                    return FormValidation.error("Must be a number");
                }

                if (number <= 0) {
                    return FormValidation.error("Must be a positive number");
                }

                return FormValidation.ok();
            }
        }

        public FormValidation doCheckIdleTerminationInMinutes(@QueryParameter String idleTerminationInMinutes) {
            if (Strings.isNullOrEmpty(idleTerminationInMinutes)) {
                return FormValidation.error("Must be set");
            } else {
                int number;

                try {
                    number = Integer.parseInt(idleTerminationInMinutes);
                } catch (Exception e) {
                    return FormValidation.error("Must be a number");
                }

                return FormValidation.ok();
            }
        }

        public FormValidation doCheckInstanceCap(@QueryParameter String instanceCap) {
            return doCheckNonNegativeNumber(instanceCap);
        }

        public FormValidation doCheckSizeId(@RelativePath("..") @QueryParameter String authToken) {
            return Cloud.DescriptorImpl.doCheckApiToken(authToken);
        }

        public FormValidation doCheckImageId(@RelativePath("..") @QueryParameter String authToken) {
            return Cloud.DescriptorImpl.doCheckApiToken(authToken);
        }

        public FormValidation doCheckRegionId(@RelativePath("..") @QueryParameter String authToken) {
            return Cloud.DescriptorImpl.doCheckApiToken(authToken);
        }

        public ListBoxModel doFillFixedInstanceSizeIdItems(@RelativePath("..") @QueryParameter String apiToken) throws Exception {

            List<AvailableHardwareFlavour> availableSizes = OneAndOne.getAvailableSizes(apiToken);
            ListBoxModel model = new ListBoxModel();

            for (AvailableHardwareFlavour size : availableSizes) {
                model.add(size.getName(), size.getId());
            }

            return model;
        }

        public ListBoxModel doFillApplianceIdItems(@RelativePath("..") @QueryParameter String apiToken) throws Exception {

            List<ServerAppliancesResponse> availableAppliances = OneAndOne.getAvailableAppliances(apiToken);
            ListBoxModel model = new ListBoxModel();

            for (ServerAppliancesResponse appliance : availableAppliances) {
                model.add(appliance.getName(), appliance.getId());
            }

            return model;
        }
    }

    /**
     * Gets the descriptor for this instance.
     * {@link Descriptor} is a singleton for every concrete {@link Describable}
     * implementation, so if {@code a.getClass() == b.getClass()} then by default
     * {@code a.getDescriptor() == b.getDescriptor()} as well.
     * (In rare cases a single implementation class may be used for instances with distinct descriptors.)
     */
    @Override
    public Descriptor<SlaveTemplate> getDescriptor() {
        return Jenkins.getInstance().getDescriptor(getClass());
    }

    public String getName() {
        return name;
    }

    public String getFixedInstanceSizeId() {
        return fixedInstanceSizeId;
    }

    public String getLabels() {
        return labels;
    }

    public String getLabelString() {
        return labelString;
    }

    public boolean isLabellessJobsAllowed() {
        return labellessJobsAllowed;
    }

    public Set<LabelAtom> getLabelSet() {
        return labelSet;
    }

    public String getApplianceId() {
        return applianceId;
    }

    public String getUsername() {
        return username;
    }

    public String getWorkspacePath() {
        return workspacePath;
    }

    public int getNumExecutors() {
        return numExecutors;
    }

    public int getIdleTerminationInMinutes() {
        return idleTerminationInMinutes;
    }

    public int getInstanceCap() {
        return instanceCap;
    }

    public String getInitScript() {
        return initScript;
    }

    public Integer getSshPort() {
        return sshPort;
    }

    private static int tryParseInteger(final String integerString, final int defaultValue) {
        try {
            return Integer.parseInt(integerString);
        }
        catch (NumberFormatException e) {
            LOGGER.log(Level.INFO, "Invalid integer {0}, defaulting to {1}", new Object[] {integerString, defaultValue});
            return defaultValue;
        }
    }

    protected Object readResolve() {
        labelSet = Label.parse(labels);
        return this;
    }
}
