package org.jenkinsci.plugins.oneandonecloudserver;

import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class ServerName {
    private static final String PREFIX = "jenkins";
    private static final String CLOUD_REGEX = "([a-zA-Z0-9\\.]+)";
    private static final String SLAVE_REGEX = "([a-zA-Z0-9\\.]+)";
    private static final String UUID_REGEX = "\\p{XDigit}{8}-\\p{XDigit}{4}-\\p{XDigit}{4}-\\p{XDigit}{4}-\\p{XDigit}{12}";

    private static final String SERVER_REGEX = PREFIX + "-" + CLOUD_REGEX + "-" + SLAVE_REGEX + "-" + UUID_REGEX;

    private static final Pattern CLOUD_PATTERN = Pattern.compile("^" + CLOUD_REGEX + "$");
    private static final Pattern SLAVE_PATTERN = Pattern.compile("^" + SLAVE_REGEX + "$");
    private static final Pattern SERVER_PATTERN = Pattern.compile("^" + SERVER_REGEX + "$");


    private ServerName() {
        throw new AssertionError();
    }

    public static boolean isValidCloudName(final String cloudName) {
        return CLOUD_PATTERN.matcher(cloudName).matches();
    }

    public static boolean isValidSlaveName(final String slaveName) {
        return SLAVE_PATTERN.matcher(slaveName).matches();
    }

    public static String generateServerName(final String cloudName, final String slaveName) {
        return PREFIX + "-" + cloudName + "-" + slaveName + "-" + UUID.randomUUID().toString();
    }

    public static boolean isServerInstanceOfCloud(final String serverName, final String cloudName) {
        Matcher m = SERVER_PATTERN.matcher(serverName);
        return m.matches() && m.group(1).equals(cloudName);
    }

    public static boolean isServerInstanceOfSlave(final String serverName, final String cloudName, final String slaveName) {
        Matcher m = SERVER_PATTERN.matcher(serverName);
        return m.matches() && m.group(1).equals(cloudName) && m.group(2).equals(slaveName);
    }
}
