package org.jenkinsci.plugins.oneandonecloudserver;

import com.google.common.base.Strings;
import com.oneandone.rest.POJO.Response.ServerIPs;
import com.oneandone.rest.POJO.Response.ServerResponse;
import com.oneandone.rest.client.RestClientException;
import com.trilead.ssh2.Connection;
import com.trilead.ssh2.SCPClient;
import com.trilead.ssh2.Session;
import hudson.Util;
import hudson.model.TaskListener;
import hudson.remoting.Channel;
import hudson.slaves.SlaveComputer;
import hudson.util.TimeUnit2;
import jenkins.model.Jenkins;
import org.apache.commons.io.IOUtils;

import java.io.IOException;
import java.io.PrintStream;
import java.net.SocketTimeoutException;
import java.text.SimpleDateFormat;
import java.util.*;
import java.util.logging.Level;
import java.util.logging.Logger;

import static java.lang.String.format;

public class ComputerLauncher extends hudson.slaves.ComputerLauncher {

    private static final Logger LOGGER = Logger.getLogger(Cloud.class.getName());

    private static abstract class JavaInstaller {
        protected abstract String getInstallCommand(String javaVersion);

        protected abstract String checkPackageManager();

        protected boolean isUsable(Connection conn, PrintStream logger) throws IOException, InterruptedException {
            return checkCommand(conn, logger, checkPackageManager());
        }

        private boolean checkCommand(Connection conn, PrintStream logger, String command) throws IOException, InterruptedException {
            logger.println("Checking: " + command);
            return conn.exec(command, logger) == 0;
        }

        protected int installJava(Connection conn, PrintStream logger, Iterable<String> javaVersion) throws IOException, InterruptedException {
            int result = 1;
            for (String version : javaVersion) {
                result = conn.exec(getInstallCommand(version), logger);
                if (result == 0) {
                    return result;
                }
            }
            return result;
        }
    }

    private static final List<String> VALID_VERSIONS = Arrays.asList("1.8", "1.7", "1.9");

    private static final Collection<JavaInstaller> INSTALLERS = new HashSet<JavaInstaller>() {{
        add(new JavaInstaller() { // apt
            @Override
            protected String getInstallCommand(String javaVersion) {
                return "apt-get update -q && apt-get install -y " + getPackageName(javaVersion);
            }

            @Override
            protected String checkPackageManager() {
                return "which apt-get";
            }

            private String getPackageName(String javaVersion) {
                return "openjdk-" + javaVersion.replaceFirst("1.", "") + "-jre-headless";
            }
        });
        add(new JavaInstaller() { // yum
            @Override
            protected String getInstallCommand(String javaVersion) {
                return "yum install -y " + getPackageName(javaVersion);
            }

            @Override
            protected String checkPackageManager() {
                return "which yum";
            }

            private String getPackageName(String javaVersion) {
                return "java-" + javaVersion + ".0-openjdk-headless";
            }
        });
    }};

    /**
     * Connects to the given {@link Computer} via SSH and installs Java/Jenkins agent if necessary.
     */
    @Override
    public void launch(SlaveComputer _computer, TaskListener listener) {

        Computer computer = (Computer)_computer;
        PrintStream logger = listener.getLogger();

        Date startDate = new Date();
        logger.println("Start time: " + getUtcDate(startDate));

        final Connection conn;
        Connection cleanupConn = null;
        boolean successful = false;

        try {
            conn = connectToSsh(computer, logger);
            cleanupConn = conn;
            logger.println("Authenticating as " + computer.getRemoteAdmin());
            if (!conn.authenticateWithPublicKey(computer.getRemoteAdmin(), computer.getNode().getPrivateKey().toCharArray(), "")) {
                logger.println("Authentication failed");
                throw new Exception("Authentication failed");
            }

            final SCPClient scp = conn.createSCPClient();

            if (!runInitScript(computer, logger, conn, scp)) {
                return;
            }

            if (!installJava(logger, conn)) {
                return;
            }

            logger.println("Copying slave.jar");
            scp.put(Jenkins.getInstance().getJnlpJars("slave.jar").readFully(), "slave.jar","/tmp", "0775");
            String jvmOpts = Util.fixNull(computer.getNode().getJvmOpts());
            String launchString = "java " + jvmOpts + " -jar /tmp/slave.jar";
            logger.println("Launching slave agent: " + launchString);
            final Session sess = conn.openSession();
            sess.execCommand(launchString);
            computer.setChannel(sess.getStdout(), sess.getStdin(), logger, new Channel.Listener() {
                @Override
                public void onClosed(Channel channel, IOException cause) {
                    sess.close();
                    conn.close();
                }
            });

            successful = true;
        } catch (Exception e) {
            LOGGER.log(Level.WARNING, e.getMessage(), e);
            try {
                Jenkins.getInstance().removeNode(computer.getNode());
            } catch (Exception ee) {
                ee.printStackTrace(logger);
            }
            e.printStackTrace(logger);
        } finally {
            Date endDate = new Date();
            logger.println("Done setting up at: " + getUtcDate(endDate));
            logger.println("Done in " + TimeUnit2.MILLISECONDS.toSeconds(endDate.getTime() - startDate.getTime()) + " seconds");
            if(cleanupConn != null && !successful) {
                cleanupConn.close();
            }
        }
    }

    private boolean runInitScript(final Computer computer, final PrintStream logger, final Connection conn, final SCPClient scp)
            throws IOException, InterruptedException {

        String initScript = Util.fixEmptyAndTrim(computer.getNode().getInitScript());

        if (initScript == null) {
            return true;
        }
        if (conn.exec("test -e ~/.hudson-run-init", logger) == 0) {
            return true;
        }

        logger.println("Executing init script");
        scp.put(initScript.getBytes("UTF-8"), "init.sh", "/tmp", "0700");
        Session session = conn.openSession();
        session.requestDumbPTY(); // so that the remote side bundles stdout and stderr
        session.execCommand(buildUpCommand(computer, "/tmp/init.sh"));

        session.getStdin().close();    // nothing to write here
        session.getStderr().close();   // we are not supposed to get anything from stderr
        IOUtils.copy(session.getStdout(), logger);

        int exitStatus = waitCompletion(session);
        if (exitStatus != 0) {
            logger.println("init script failed: exit code=" + exitStatus);
            return false;
        }
        session.close();

        // Needs a tty to run sudo.
        session = conn.openSession();
        session.requestDumbPTY(); // so that the remote side bundles stdout and stderr
        session.execCommand(buildUpCommand(computer, "touch ~/.hudson-run-init"));
        session.close();

        return true;
    }

    private boolean installJava(final PrintStream logger, final Connection conn) throws IOException, InterruptedException {
        logger.println("Verifying that java exists");
        if (conn.exec("java -fullversion", logger) != 0) {
            logger.println("Try to install one of these Java-versions: " + VALID_VERSIONS);
            logger.println("Trying to find a working package manager");
            for (JavaInstaller installer : INSTALLERS) {
                if (!installer.isUsable(conn, logger)) {
                    continue;
                }
                if (installer.installJava(conn, logger, VALID_VERSIONS) == 0) {
                    return true;
                }
            }

            logger.println("Java could not be installed using any of the supported package managers");
            return false;
        }
        return true;
    }

    private Connection connectToSsh(Computer computer, PrintStream logger) throws RestClientException, IOException {

        final long timeout = TimeUnit2.MINUTES.toMillis(computer.getCloud().getTimeoutMinutes());
        final long startTime = System.currentTimeMillis();
        final int sleepTime = 10;

        long waitTime;

        while ((waitTime = System.currentTimeMillis() - startTime) < timeout) {

            // Hack to fetch this each time through the loop to get the latest information.
            final ServerResponse server = OneAndOne.getServer(
                    computer.getCloud().getApiToken(),
                    computer.getNode().getServerId());

            if (isServerStarting(server)) {
                logger.println("Waiting for server to enter POWERED_ON state. Sleeping " + sleepTime + " seconds.");
            } else {
                try {
                    final String host = getIpAddress(computer);

                    if (Strings.isNullOrEmpty(host) || "0.0.0.0".equals(host)) {
                        logger.println("No ip address yet, your host is most likely waiting for an ip address.");
                    } else {
                        int port = computer.getSshPort();

                        Connection conn = getServerConnection(host, port, logger);
                        if (conn != null) {
                            return conn;
                        }
                    }
                } catch (IOException e) {
                    // Ignore, we'll retry.
                }
                logger.println("Waiting for SSH to come up. Sleeping " + sleepTime + " seconds.");
            }

            sleep(sleepTime);
        }

        throw new RuntimeException(format(
                "Timed out after %d seconds of waiting for ssh to become available (max timeout configured is %s)",
                waitTime / 1000,
                timeout / 1000));
    }

    private static boolean isServerStarting(final ServerResponse server) {

        switch (server.getStatus().getState()) {
            case CONFIGURING:
            case DEPLOYING:
            case POWERING_ON:
            case REBOOTING:
                return true;

            case POWERED_ON:
                return false;

            default:
                throw new IllegalStateException("Server has unexpected state: " + server.getStatus().getState());
        }
    }

    private Connection getServerConnection(String host, int port, PrintStream logger) throws IOException {
        logger.println("Connecting to " + host + " on port " + port + ". ");
        Connection conn = new Connection(host, port);
        try {
            conn.connect();
//            conn.connect(null, 10 * 1000, 10 * 1000);
        } catch (SocketTimeoutException e) {
            return null;
        }
        logger.println("Connected via SSH.");
        return conn;
    }

    private static String getIpAddress(Computer computer) throws RestClientException, IOException {
        ServerResponse instance = computer.updateInstanceDescription();

        for (final ServerIPs network : instance.getIps()) {
            String host = network.getIp();
            if (host != null) {
                return host;
            }
        }

        return null;
    }

    private int waitCompletion(Session session) throws InterruptedException {
        // I noticed that the exit status delivery often gets delayed. Wait up to 1 sec.
        for (int i = 0; i < 10; i++) {
            Integer r = session.getExitStatus();
            if (r != null) return r;
            Thread.sleep(100);
        }
        return -1;
    }

    protected String buildUpCommand(Computer computer, String command) {
        if (!computer.getRemoteAdmin().equals("root")) {
//            command = computer.getRootCommandPrefix() + " " + command;
        }
        return command;
    }

    private static void sleep(int seconds) {
        try {
            Thread.sleep(seconds * 1000);
        } catch (InterruptedException e) {
            // Ignore
        }
    }

    private String getUtcDate(Date date) {
        SimpleDateFormat utcFormat = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss z");
        utcFormat.setTimeZone(TimeZone.getTimeZone("UTC"));
        return utcFormat.format(date);
    }

}
