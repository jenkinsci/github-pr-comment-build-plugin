package com.adobe.jenkins.github_pr_comment_build;

import hudson.Extension;
import hudson.model.Job;
import hudson.model.Run;
import jenkins.branch.BranchProperty;
import jenkins.branch.BranchPropertyDescriptor;
import jenkins.branch.JobDecorator;
import org.kohsuke.stapler.DataBoundConstructor;

/**
 * Allows a GitHub pull request update to trigger an immediate build.
 */
public class TriggerPRReviewBranchProperty extends BranchProperty {
    private final boolean allowUntrusted;

    /**
     * Constructor.
     */
    @DataBoundConstructor
    public TriggerPRReviewBranchProperty(boolean allowUntrusted) {
        this.allowUntrusted = allowUntrusted;
    }

    public boolean isAllowUntrusted() {
        return allowUntrusted;
    }


    @Override
    public <P extends Job<P, B>, B extends Run<P, B>> JobDecorator<P, B> jobDecorator(Class<P> clazz) {
        return null;
    }

    @Extension
    public static class DescriptorImpl extends BranchPropertyDescriptor {

        @Override
        public String getDisplayName() {
            return Messages.TriggerPRReviewBranchProperty_trigger_on_pull_request_review();
        }

    }
}
