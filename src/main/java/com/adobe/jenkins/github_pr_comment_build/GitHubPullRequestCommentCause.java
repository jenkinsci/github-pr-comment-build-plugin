package com.adobe.jenkins.github_pr_comment_build;

import hudson.model.Cause;

/**
 * Created by saville on 10/13/2016.
 */
public final class GitHubPullRequestCommentCause extends Cause {
    private final String commentUrl;
    private final String commentBody;

    /**
     * Constructor.
     * @param commentUrl the URL for the GitHub comment
     * @param commentBody the body for the GitHub comment
     */
    public GitHubPullRequestCommentCause(String commentUrl, String commentBody) {
        this.commentUrl = commentUrl;
        this.commentBody = commentBody;
    }

    @Override
    public String getShortDescription() {
        return "GitHub pull request comment";
    }

    /**
     * Retrieves the URL for the GitHub comment for this cause.
     * @return the URL for the GitHub comment
     */
    public String getCommentUrl() {
        return commentUrl;
    }

    /**
     * Retrieves the body for the GitHub comment for this cause.
     * @return the body for the GitHub comment
     */
    public String getCommentBody() {
        return commentBody;
    }
}
