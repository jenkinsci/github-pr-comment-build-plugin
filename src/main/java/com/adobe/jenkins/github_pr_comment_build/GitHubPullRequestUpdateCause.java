package com.adobe.jenkins.github_pr_comment_build;

import hudson.model.Cause;
import org.jenkinsci.plugins.scriptsecurity.sandbox.whitelists.Whitelisted;
import org.kohsuke.stapler.export.Exported;

import java.io.Serializable;

/**
 * Created by saville on 10/13/2016.
 */
public final class GitHubPullRequestUpdateCause extends Cause implements Serializable {
    private final String pullRequestUrl;

    /**
     * Constructor.
     * @param pullRequestUrl the URL for the GitHub comment
     */
    public GitHubPullRequestUpdateCause(String pullRequestUrl) {
        this.pullRequestUrl = pullRequestUrl;
    }

    @Whitelisted
    @Override
    public String getShortDescription() {
        return "GitHub pull request update";
    }

    /**
     * Retrieves the URL for the GitHub pull request for this cause.
     * @return the URL for the GitHub pull request
     */
    @Whitelisted
    @Exported(visibility = 3)
    public String getPullRequestUrl() {
        return pullRequestUrl;
    }
}