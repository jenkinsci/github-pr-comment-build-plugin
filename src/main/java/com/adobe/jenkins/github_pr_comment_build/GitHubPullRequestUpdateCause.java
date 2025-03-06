package com.adobe.jenkins.github_pr_comment_build;

import hudson.model.Cause;
import org.jenkinsci.plugins.scriptsecurity.sandbox.whitelists.Whitelisted;
import org.kohsuke.stapler.export.Exported;

import java.io.Serializable;

/**
 * Created by saville on 10/13/2016.
 */
public final class GitHubPullRequestUpdateCause extends Cause implements Serializable {
    private final String updateAuthor;
    private final String pullRequestUrl;

    /**
     * Constructor.
     * @param updateAuthor the author for the GitHub update
     * @param pullRequestUrl the URL for the GitHub update
     */
    public GitHubPullRequestUpdateCause(String updateAuthor, String pullRequestUrl) {
        this.updateAuthor = updateAuthor;
        this.pullRequestUrl = pullRequestUrl;
    }

    @Whitelisted
    @Override
    public String getShortDescription() {
        return "GitHub pull request update";
    }

    /**
     * Retrieves the author of the GitHub update for this cause.
     * @return the author of the GitHub update
     */
    @Whitelisted
    @Exported(visibility = 3)
    public String getUpdateAuthor() {
        return updateAuthor;
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