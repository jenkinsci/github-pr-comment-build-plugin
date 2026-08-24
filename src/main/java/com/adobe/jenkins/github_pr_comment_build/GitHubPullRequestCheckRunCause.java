package com.adobe.jenkins.github_pr_comment_build;

import hudson.model.Cause;
import org.jenkinsci.plugins.scriptsecurity.sandbox.whitelists.Whitelisted;
import org.kohsuke.stapler.export.Exported;

import java.io.Serializable;

/**
 * Cause for a build triggered by a completed GitHub check run on a pull request.
 */
public final class GitHubPullRequestCheckRunCause extends Cause implements Serializable {
    private final String checkRunName;
    private final String conclusion;
    private final String pullRequestUrl;

    /**
     * Constructor.
     * @param checkRunName the name of the GitHub check run
     * @param conclusion the conclusion of the GitHub check run (e.g. success, neutral, failure)
     * @param pullRequestUrl the URL for the pull request the check run belongs to
     */
    public GitHubPullRequestCheckRunCause(String checkRunName, String conclusion, String pullRequestUrl) {
        this.checkRunName = checkRunName;
        this.conclusion = conclusion;
        this.pullRequestUrl = pullRequestUrl;
    }

    @Whitelisted
    @Override
    public String getShortDescription() {
        return String.format("GitHub check run '%s' completed (%s)", checkRunName, conclusion);
    }

    /**
     * Retrieves the name of the GitHub check run for this cause.
     * @return the name of the GitHub check run
     */
    @Whitelisted
    @Exported(visibility = 3)
    public String getCheckRunName() {
        return checkRunName;
    }

    /**
     * Retrieves the conclusion of the GitHub check run for this cause.
     * @return the conclusion of the GitHub check run
     */
    @Whitelisted
    @Exported(visibility = 3)
    public String getConclusion() {
        return conclusion;
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
