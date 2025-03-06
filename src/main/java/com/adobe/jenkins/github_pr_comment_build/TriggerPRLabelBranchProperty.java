package com.adobe.jenkins.github_pr_comment_build;

import hudson.Extension;
import hudson.model.Job;
import hudson.model.Run;
import jenkins.branch.JobDecorator;
import org.kohsuke.stapler.DataBoundConstructor;

/**
 * Allows a GitHub pull request comment to trigger an immediate build based on a comment string.
 */
public class TriggerPRLabelBranchProperty extends TriggerBranchProperty {
    /**
     * The comment body to trigger a new build on.
     */
    private final String label;

    /**
     * Constructor.
     *
     * @param label the comment body to trigger a new build on
     */
    @DataBoundConstructor
    public TriggerPRLabelBranchProperty(String label) {
        this.label = label;
    }

    /**
     * The comment body to trigger a new build on.
     *
     * @return the comment body to use
     */
    public String getLabel() {
        return this.label;
    }

    @Override
    public <P extends Job<P, B>, B extends Run<P, B>> JobDecorator<P, B> jobDecorator(Class<P> clazz) {
        return null;
    }

    @Extension
    public static class DescriptorImpl extends TriggerBranchPropertyDescriptorImpl {

        @Override
        public String getDisplayName() {
            return Messages.TriggerPRLabelBranchProperty_trigger_on_pull_request_label();
        }

    }
}
