package com.adobe.jenkins.github_pr_comment_build;

import hudson.Extension;
import org.kohsuke.stapler.DataBoundConstructor;

/**
 * Allows a GitHub pull request comment to trigger an immediate build based on a comment string.
 */
public class TriggerPRCommentBranchProperty extends TriggerBranchProperty {
    /**
     * The comment body to trigger a new build on.
     */
    private final String commentBody;
    /**
     * Whether to react to the comment if a build is successfully triggered.
     */
    private final boolean addReaction;

    /**
     * Constructor.
     * @param commentBody the comment body to trigger a new build on
     * @param addReaction whether to react to the comment if a build is successfully triggered
     */
    @DataBoundConstructor
    public TriggerPRCommentBranchProperty(String commentBody, boolean addReaction) {
        this.commentBody = commentBody;
        this.addReaction = addReaction;
    }

    /**
     * Backward-compatible Constructor, e.g. for Seed Jobs. Assumes no reaction is required.
     * @param commentBody the comment body to trigger a new build on
     */
    @DataBoundConstructor
    public TriggerPRCommentBranchProperty(String commentBody) {
        this.commentBody = commentBody;
        this.addReaction = false;
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

    /**
     * Whether to react to the comment if a build is successfully triggered.
     * @return if a reaction should be added
     */
    public boolean getAddReaction() {
        return addReaction;
    }

    @Extension
    public static class DescriptorImpl extends TriggerBranchPropertyDescriptorImpl {

        @Override
        public String getDisplayName() {
            return Messages.TriggerPRCommentBranchProperty_trigger_on_pull_request_comment();
        }
    }
}
