package org.jenkinsci.plugins.oneandonecloudserver;

import com.oneandone.rest.POJO.Response.AvailableHardwareFlavour;
import com.oneandone.rest.POJO.Response.ServerAppliancesResponse;
import com.oneandone.rest.POJO.Response.ServerResponse;
import com.oneandone.rest.client.RestClientException;
import com.oneandone.sdk.OneAndOneApi;

import java.io.IOException;
import java.util.*;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Class containing various utility methods.
 */
public class OneAndOne {

    public OneAndOne() {
        throw new AssertionError();
    }

    private static final Logger LOGGER = Logger.getLogger(OneAndOne.class.getName());

    /**
     * Fetches all available server sizes.
     * @param apiToken the 1&1 API authorisation token
     * @return a list of {@link AvailableHardwareFlavour}s
     * @throws RestClientException
     * @throws IOException
     */
    static List<AvailableHardwareFlavour> getAvailableSizes(String apiToken) throws RestClientException, IOException {
        OneAndOneApi apiClient = new OneAndOneApi();
        apiClient.setToken(apiToken);

        List<AvailableHardwareFlavour> availableSizes = apiClient.getServerApi().getAvailableFixedServers();

        return availableSizes;
    }

    /**
     * Fetches all available appliances.
     *
     * @param apiToken the 1&1 API authorisation token
     * @return a list of {@link ServerAppliancesResponse}s
     * @throws RestClientException
     * @throws IOException
     */
    static List<ServerAppliancesResponse> getAvailableAppliances(String apiToken) throws RestClientException, IOException {
        OneAndOneApi apiClient = new OneAndOneApi();
        apiClient.setToken(apiToken);

        List<ServerAppliancesResponse> availableAppliances = apiClient.getServerAppliancesApi().getServerAppliances(0, 0, null, null, null);

        return availableAppliances;
    }

    private static class DestroyInfo {
        public final String apiToken;
        public final String serverId;

        public DestroyInfo(String apiKey, String serverId) {
            this.apiToken = apiKey;
            this.serverId = serverId;
        }
    }

    /**
     * Fetches a list of all available servers.
     *
     * @return a list of all available servers.
     */
    static List<ServerResponse> getServers(String apiToken) throws RestClientException, IOException {
        LOGGER.log(Level.INFO, "Listing all servers");
        OneAndOneApi apiClient = new OneAndOneApi();
        apiClient.setToken(apiToken);

        return apiClient.getServerApi().getAllServers(0, 0, null, null, null);
    }

    /**
     * Fetches information for the specified server.
     * @param apiToken the API authentication token to use
     * @param serverId the ID of the server to query
     * @return information for the specified server
     * @throws RestClientException
     * @throws IOException
     */
    static ServerResponse getServer(String apiToken, String serverId) throws RestClientException, IOException {
        LOGGER.log(Level.INFO, "Fetching server " + serverId);
        OneAndOneApi apiClient = new OneAndOneApi();
        apiClient.setToken(apiToken);

        return apiClient.getServerApi().getServer(serverId);
    }

    private static final List<DestroyInfo> toBeDestroyedServers = new ArrayList<DestroyInfo>();

    // Sometimes servers have pending events during which you can't destroy them.
    // One of such events is starting up a new server. Therefor, continuous attempts to
    // destroy servers are being done in a separate thread.
    private static final Thread serverDestroyer = new Thread(new Runnable() {
        @Override
        public void run() {

            do {
                String apiToken = null;
                String previousPassword = null;
                OneAndOneApi apiClient = null;
                ServerResponse existingServer = null;
                boolean failedToDestroy = false;

                synchronized (toBeDestroyedServers) {
                    Iterator<DestroyInfo> it = toBeDestroyedServers.iterator();
                    while (it.hasNext()) {
                        DestroyInfo di = it.next();

                        // the list should be sorted by di.apiToken to prevent unnecessary OneAndOneApi recreation
                        if (di.apiToken != apiToken) {
                            apiToken = di.apiToken;
                            try {
                                apiClient = new OneAndOneApi();
                                apiClient.setToken(di.apiToken);
                            } catch (Exception e) {
                                LOGGER.warning("Failed to instantiate 1&1 API client.");
                                LOGGER.log(Level.WARNING, e.getMessage(), e);
                            }
                            existingServer = null;
                        }

                        try {
                            LOGGER.info("Trying to destroy server " + di.serverId);
                            apiClient.getServerApi().deleteServer(di.serverId, false);
                            LOGGER.info("Server " + di.serverId + " is destroyed.");
                            it.remove();
                        } catch (Exception e) {
                            // check if such server even existed in the first place
                            if (existingServer == null) {
                                try {
                                    existingServer = apiClient.getServerApi().getServer(di.serverId);
                                } catch (RestClientException rcex) {
                                    LOGGER.warning("Failed to retrieve Server.");
                                    LOGGER.log(Level.WARNING, rcex.getMessage(), rcex);
                                } catch (IOException ioex) {
                                    LOGGER.warning("Failed to retrieve Server.");
                                    LOGGER.log(Level.WARNING, ioex.getMessage(), ioex);
                                }
                            }
                            if (existingServer == null) {
                                // The requested server doesn't exist, remove it from the toBeDestroyedServers iterator
                                LOGGER.info(String.format("Server %s doesn't exist, removing it from the list.", di.serverId));
                                it.remove();
                                continue;
                            }
                            // The requested server might exist, so let's retry later
                            failedToDestroy = true;
                            LOGGER.warning("Failed to destroy server " + di.serverId);
                            LOGGER.log(Level.WARNING, e.getMessage(), e);
                        }
                    }

                    if (failedToDestroy) {
                        LOGGER.info("Retrying to destroy the server in about 10 seconds");
                        try {
                            // sleep for 10 seconds, but wake up earlier if notified
                            toBeDestroyedServers.wait(10000);
                        } catch (InterruptedException e) {
                            // ignore
                        }
                    } else {
                        LOGGER.info("Waiting on more servers to destroy");
                        while (toBeDestroyedServers.isEmpty()) {
                            try {
                                toBeDestroyedServers.wait();
                            } catch (InterruptedException e) {
                                // ignore
                            }
                        }
                    }
                }
            } while (true);
        }
    });

    static void tryDestroyServerAsync(final String apiToken, final String serverId) {
        synchronized (toBeDestroyedServers) {
            LOGGER.info(String.format("Adding server to destroy %s", serverId));

            toBeDestroyedServers.add(new DestroyInfo(apiToken, serverId));

            // sort by username
            Collections.sort(toBeDestroyedServers, new Comparator<DestroyInfo>() {
                @Override
                public int compare(DestroyInfo o1, DestroyInfo o2) {
                    return o1.apiToken.compareTo(o2.apiToken);
                }
            });

            toBeDestroyedServers.notifyAll();

            if (!serverDestroyer.isAlive()) {
                serverDestroyer.start();
            }
        }
    }

}
