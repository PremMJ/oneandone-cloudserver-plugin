package org.jenkinsci.plugins.oneandonecloudserver;

import hudson.model.Descriptor;
import hudson.slaves.CloudSlaveRetentionStrategy;
import hudson.util.TimeUnit2;

public class RetentionStrategy extends CloudSlaveRetentionStrategy<Computer> {

    public static class DescriptorImpl extends Descriptor<hudson.slaves.RetentionStrategy<?>> {
        @Override
        public String getDisplayName() {
            return "1&1";
        }
    }

    public void start(Computer computer) {
        computer.connect(false);
    }

    @Override
    protected long checkCycle() {
        return 1; // ask Jenkins to check every 1 minute, though it might decide to check in 2 or 3 (or longer?)
    }
}
