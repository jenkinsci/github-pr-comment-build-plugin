package com.adobe.jenkins.github_pr_comment_build;

import hudson.Extension;
import org.kohsuke.stapler.DataBoundConstructor;

/**
 * Allows a completed GitHub check run on a pull request to trigger an immediate build.
 */
public class TriggerPRCheckRunBranchProperty extends TriggerBranchProperty {

    /**
     * Constructor.
     */
    @DataBoundConstructor
    public TriggerPRCheckRunBranchProperty() {}

    @Extension
    public static class DescriptorImpl extends TriggerBranchPropertyDescriptorImpl {

        @Override
        public String getDisplayName() {
            return Messages.TriggerPRCheckRunBranchProperty_trigger_on_pull_request_check_run();
        }
    }
}
