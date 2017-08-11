package org.jenkinsci.plugins.oneandonecloudserver;

import com.oneandone.rest.POJO.Response.ServerResponse;
import com.oneandone.rest.client.RestClientException;
import com.oneandone.sdk.OneAndOneApi;
import hudson.slaves.AbstractCloudComputer;

import java.io.IOException;
import java.util.logging.Level;
import java.util.logging.Logger;

public class Computer extends AbstractCloudComputer<Slave> {

    private static final Logger LOGGER = Logger.getLogger(Computer.class.getName());

    private final String apiToken;
    private String serverId;

    public Computer(Slave slave) {
        super(slave);
        apiToken = slave.getCloud().getApiToken();
        serverId = slave.getServerId();
    }

    public ServerResponse updateInstanceDescription() throws RestClientException, IOException {
        OneAndOneApi apiClient = new OneAndOneApi();
        apiClient.setToken(apiToken);

        return apiClient.getServerApi().getServer(serverId);
    }

    @Override
    protected void onRemoved() {
        super.onRemoved();

        LOGGER.info("Slave removed, deleting server " + serverId);
        OneAndOne.tryDestroyServerAsync(apiToken, serverId);
    }

    public Cloud getCloud() {
        return getNode().getCloud();
    }

    public int getSshPort() {
        return getNode().getSshPort();
    }


    public String getRemoteAdmin() {
        return getNode().getRemoteAdmin();
    }

    public long getStartTimeMillis() {
        return getNode().getStartTimeMillis();
    }

}
