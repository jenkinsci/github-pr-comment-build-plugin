package com.adobe.jenkins.github_pr_comment_build;

import hudson.model.Cause;
import org.jenkinsci.plugins.scriptsecurity.sandbox.whitelists.Whitelisted;
import org.kohsuke.stapler.export.Exported;

import java.io.Serializable;

/**
 * Created by Agyaey on 04/04/2023.
 */
public final class GitHubPullRequestLabelCause extends Cause implements Serializable {
    private final String labelUrl;
    private final String labellingAuthor;
    private final String label;

    /**
     * Constructor.
     *
     * @param labelUrl        the URL for the GitHub Label
     * @param labellingAuthor the author of the GitHub Label
     * @param label           the body for the GitHub Label
     */
    public GitHubPullRequestLabelCause(String labelUrl, String labellingAuthor, String label) {
        this.labelUrl = labelUrl;
        this.labellingAuthor = labellingAuthor;
        this.label = label;
    }

    @Whitelisted
    @Override
    public String getShortDescription() {
        return String.format("GitHub pull request label \"%s\" by %s", label, labellingAuthor);
    }

    /**
     * Retrieves the URL for the GitHub Label for this cause.
     *
     * @return the URL for the GitHub Label
     */
    @Whitelisted
    @Exported(visibility = 3)
    public String getLabelUrl() {
        return labelUrl;
    }

    /**
     * Retrieves the author of the GitHub Label for this cause.
     *
     * @return the author of the GitHub Label
     */
    @Whitelisted
    @Exported(visibility = 3)
    public String getLabellingAuthor() {
        return labellingAuthor;
    }

    /**
     * Retrieves the body for the GitHub Label for this cause.
     *
     * @return the body for the GitHub Label
     */
    @Whitelisted
    @Exported(visibility = 3)
    public String getLabel() {
        return label;
    }
}
