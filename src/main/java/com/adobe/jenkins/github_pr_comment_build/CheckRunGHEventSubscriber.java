package com.adobe.jenkins.github_pr_comment_build;

import com.cloudbees.jenkins.GitHubRepositoryName;
import hudson.Extension;
import net.sf.json.JSONArray;
import net.sf.json.JSONObject;
import org.kohsuke.github.GHEvent;

import java.util.Set;
import java.util.logging.Level;
import java.util.logging.Logger;

import static com.google.common.collect.Sets.immutableEnumSet;
import static org.kohsuke.github.GHEvent.CHECK_RUN;

/**
 * This subscriber manages {@link GHEvent} CHECK_RUN completions. A pull request's own checks may
 * all pass before a slower third-party check (e.g. an AI review bot) finishes, so nothing
 * re-evaluates automerge once that check finally lands unless something re-triggers the job.
 */
@Extension
public class CheckRunGHEventSubscriber extends BasePRGHEventSubscriber<TriggerPRCheckRunBranchProperty, Void> {
    /**
     * Logger.
     */
    private static final Logger LOGGER = Logger.getLogger(CheckRunGHEventSubscriber.class.getName());

    /**
     * String representing the completed action on a check run - only a completed check run has a
     * final conclusion worth re-evaluating automerge against.
     */
    private static final String ACTION_COMPLETED = "completed";

    @Override
    protected Class<TriggerPRCheckRunBranchProperty> getTriggerClass() {
        return TriggerPRCheckRunBranchProperty.class;
    }

    @Override
    protected Set<GHEvent> events() {
        return immutableEnumSet(CHECK_RUN);
    }

    /**
     * Handles completions of check runs on pull requests.
     * @param event only CHECK_RUN events
     * @param payload payload of gh-event. Never blank
     */
    @Override
    protected void onEvent(GHEvent event, String payload) {
        JSONObject json = JSONObject.fromObject(payload);

        String action = json.getString("action");
        if (!ACTION_COMPLETED.equals(action)) {
            LOGGER.log(Level.FINER, "Check run action is not completed ({0}), ignoring", action);
            return;
        }

        JSONObject checkRun = json.getJSONObject("check_run");
        String checkRunName = checkRun.getString("name");
        String conclusion = checkRun.optString("conclusion", null);
        String author = json.getJSONObject("sender").getString("login");

        // Only present for pull requests whose head branch lives in the same repository as the
        // check run - a forked pull request's check runs do not carry this back-reference, so
        // there is nothing to re-trigger for those.
        JSONArray pullRequests = checkRun.optJSONArray("pull_requests");
        if (pullRequests == null || pullRequests.isEmpty()) {
            LOGGER.log(Level.FINE, "Check run '{0}' completed with no associated pull requests, ignoring",
                    checkRunName);
            return;
        }

        String repoUrl = getRepoUrl(json);
        final GitHubRepositoryName changedRepository = getChangedRepository(repoUrl);
        if (changedRepository == null) {
            return;
        }

        for (int i = 0; i < pullRequests.size(); i++) {
            JSONObject pullRequest = pullRequests.getJSONObject(i);
            int pullRequestId = pullRequest.getInt("number");
            final String pullRequestUrl = String.format("%s/pull/%d", repoUrl, pullRequestId);

            LOGGER.log(Level.FINE, "Received completed check run '{0}' ({1}) on PR {2} for {3}",
                    new Object[] { checkRunName, conclusion, pullRequestId, repoUrl });
            checkAndRunJobs(changedRepository, pullRequestId, author, null,
                    (job, branchProp) -> new GitHubPullRequestCheckRunCause(checkRunName, conclusion, pullRequestUrl));
        }
    }
}
