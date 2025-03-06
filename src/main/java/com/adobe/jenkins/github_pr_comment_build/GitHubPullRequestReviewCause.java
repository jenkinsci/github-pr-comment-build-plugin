package com.adobe.jenkins.github_pr_comment_build;

import hudson.model.Cause;
import org.jenkinsci.plugins.scriptsecurity.sandbox.whitelists.Whitelisted;
import org.kohsuke.stapler.export.Exported;

import java.io.Serializable;

/**
 * Created by Micky Loo on 05/02/2019.
 */
public final class GitHubPullRequestReviewCause extends Cause implements Serializable {
    private final String reviewAuthor;
    private final String pullRequestUrl;

    /**
     * Constructor.
     * @param reviewAuthor the author of the GitHub review
     * @param pullRequestUrl the URL for the GitHub review
     */
    public GitHubPullRequestReviewCause(String reviewAuthor, String pullRequestUrl) {
        this.reviewAuthor = reviewAuthor;
        this.pullRequestUrl = pullRequestUrl;
    }

    @Whitelisted
    @Override
    public String getShortDescription() {
        return String.format("GitHub pull request review by %s", reviewAuthor);
    }

    /**
     * Retrieves the author of the GitHub review for this cause.
     * @return the author of the GitHub review
     */
    @Whitelisted
    @Exported(visibility = 3)
    public String getReviewAuthor() {
        return reviewAuthor;
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
