package com.adobe.jenkins.github_pr_comment_build;

import hudson.Extension;
import org.kohsuke.stapler.DataBoundConstructor;

/**
 * Allows a GitHub pull request update to trigger an immediate build.
 */
public class TriggerPRUpdateBranchProperty extends TriggerBranchProperty {

    /**
     * Constructor.
     */
    @DataBoundConstructor
    public TriggerPRUpdateBranchProperty() {}

    @Extension
    public static class DescriptorImpl extends TriggerBranchPropertyDescriptorImpl {

        @Override
        public String getDisplayName() {
            return Messages.TriggerPRUpdateBranchProperty_trigger_on_pull_request_update();
        }
    }
}