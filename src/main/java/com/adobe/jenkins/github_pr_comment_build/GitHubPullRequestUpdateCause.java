package com.adobe.jenkins.github_pr_comment_build;

import hudson.model.Cause;

/**
 * Created by saville on 10/13/2016.
 */
public final class GitHubPullRequestUpdateCause extends Cause {
    private final String pullRequestUrl;

    /**
     * Constructor.
     * @param pullRequestUrl the URL for the GitHub comment
     */
    public GitHubPullRequestUpdateCause(String pullRequestUrl) {
        this.pullRequestUrl = pullRequestUrl;
    }

    @Override
    public String getShortDescription() {
        return "GitHub pull request update";
    }

    /**
     * Retrieves the URL for the GitHub pull request for this cause.
     * @return the URL for the GitHub pull request
     */
    public String getPullRequestUrl() {
        return pullRequestUrl;
    }
}