package com.adobe.jenkins.github_pr_comment_build;

import com.cloudbees.jenkins.GitHubRepositoryName;
import hudson.Extension;
import net.sf.json.JSONObject;
import org.kohsuke.github.GHEvent;

import java.util.Set;
import java.util.logging.Level;
import java.util.logging.Logger;

import static com.google.common.collect.Sets.immutableEnumSet;
import static org.kohsuke.github.GHEvent.PULL_REQUEST_REVIEW;

/**
 * This subscriber manages {@link GHEvent} PULL_REQUEST_REVIEW edits.
 */
@Extension
public class PRReviewGHEventSubscriber extends BasePRGHEventSubscriber<TriggerPRReviewBranchProperty, Void> {
    /**
     * Logger.
     */
    private static final Logger LOGGER = Logger.getLogger(PRReviewGHEventSubscriber.class.getName());

    @Override
    protected Class<TriggerPRReviewBranchProperty> getTriggerClass() {
        return TriggerPRReviewBranchProperty.class;
    }

    @Override
    protected Set<GHEvent> events() {
        return immutableEnumSet(PULL_REQUEST_REVIEW);
    }

    /**
     * Handles updates of pull requests.
     * @param event only PULL_REQUEST_REVIEW events
     * @param payload payload of gh-event. Never blank
     */
    @Override
    protected void onEvent(GHEvent event, String payload) {
        JSONObject json = JSONObject.fromObject(payload);

        JSONObject pullRequest = json.getJSONObject("pull_request");
        final String pullRequestUrl = pullRequest.getString("html_url");
        int pullRequestId = pullRequest.getInt("number");
        String author = json.getJSONObject("sender").getString("login");
        LOGGER.fine(() -> String.format("PR Review Author: %s", author));

        String repoUrl = getRepoUrl(json);
        final GitHubRepositoryName changedRepository = getChangedRepository(repoUrl);
        if (changedRepository == null) {
            return;
        }

        LOGGER.log(Level.FINE, "Received review on PR {0} for {1}", new Object[] { pullRequestId, repoUrl });
        checkAndRunJobs(changedRepository, pullRequestId, author, null,
                (job, branchProp) -> {
            return new GitHubPullRequestReviewCause(author, pullRequestUrl);
        });
    }
}