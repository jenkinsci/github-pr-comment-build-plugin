package com.adobe.jenkins.github_pr_comment_build;

import hudson.model.Cause;
import org.jenkinsci.plugins.scriptsecurity.sandbox.whitelists.Whitelisted;

/**
 * Created by saville on 10/13/2016.
 */
public final class GitHubPullRequestCommentCause extends Cause {
    private final String commentUrl;
    private final String commentAuthor;
    private final String commentBody;

    /**
     * Constructor.
     * @param commentUrl the URL for the GitHub comment
     * @param commentAuthor the author of the GitHub comment
     * @param commentBody the body for the GitHub comment
     */
    public GitHubPullRequestCommentCause(String commentUrl, String commentAuthor, String commentBody) {
        this.commentUrl = commentUrl;
        this.commentAuthor = commentAuthor;
        this.commentBody = commentBody;
    }

    @Whitelisted
    @Override
    public String getShortDescription() {
        return "GitHub pull request comment";
    }

    /**
     * Retrieves the URL for the GitHub comment for this cause.
     * @return the URL for the GitHub comment
     */
    @Whitelisted
    public String getCommentUrl() {
        return commentUrl;
    }

    /**
     * Retrieves the author of the GitHub comment for this cause.
     * @return the author of the GitHub comment
     */
    @Whitelisted
    public String getCommentAuthor() {
        return commentAuthor;
    }

    /**
     * Retrieves the body for the GitHub comment for this cause.
     * @return the body for the GitHub comment
     */
    @Whitelisted
    public String getCommentBody() {
        return commentBody;
    }
}
