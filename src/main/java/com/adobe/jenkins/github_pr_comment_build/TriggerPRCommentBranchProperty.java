package com.adobe.jenkins.github_pr_comment_build;

import hudson.Extension;
import hudson.model.Job;
import hudson.model.Run;
import jenkins.branch.BranchProperty;
import jenkins.branch.BranchPropertyDescriptor;
import jenkins.branch.JobDecorator;
import org.kohsuke.stapler.DataBoundConstructor;

/**
 * Allows a GitHub pull request comment to trigger an immediate build based on a comment string.
 */
public class TriggerPRCommentBranchProperty extends BranchProperty {
    /**
     * The comment body to trigger a new build on.
     */
    private final String commentBody;

    /**
     * Constructor.
     * @param commentBody the comment body to trigger a new build on
     */
    @DataBoundConstructor
    public TriggerPRCommentBranchProperty(String commentBody) {
        this.commentBody = commentBody;
    }

    /**
     * The comment body to trigger a new build on.
     * @return the comment body to use
     */
    public String getCommentBody() {
        if (commentBody == null || commentBody.isEmpty()) {
            return "^REBUILD$";
        }
        return commentBody;
    }

    @Override
    public <P extends Job<P, B>, B extends Run<P, B>> JobDecorator<P, B> jobDecorator(Class<P> clazz) {
        return null;
    }

    @Extension
    public static class DescriptorImpl extends BranchPropertyDescriptor {

        @Override
        public String getDisplayName() {
            return Messages.TriggerPRCommentBranchProperty_trigger_on_pull_request_comment();
        }

    }
}