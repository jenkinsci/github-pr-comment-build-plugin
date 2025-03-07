package com.adobe.jenkins.github_pr_comment_build;

import com.cloudbees.jenkins.GitHubRepositoryName;
import hudson.Extension;
import net.sf.json.JSONObject;
import org.kohsuke.github.GHEvent;

import java.util.Set;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.regex.Pattern;

import static com.google.common.collect.Sets.immutableEnumSet;
import static org.kohsuke.github.GHEvent.PULL_REQUEST;

/**
 * This subscriber manages {@link GHEvent} Label.
 */
@Extension
public class IssueLabelGHEventSubscriber extends BasePRGHEventSubscriber<TriggerPRLabelBranchProperty, Void> {
    /**
     * Logger.
     */
    private static final Logger LOGGER = Logger.getLogger(IssueLabelGHEventSubscriber.class.getName());

    /**
     * String representing the created action on labeled PR.
     */
    private static final String ACTION_LABELED = "labeled";

    @Override
    protected Class<TriggerPRLabelBranchProperty> getTriggerClass() {
        return TriggerPRLabelBranchProperty.class;
    }

    @Override
    protected Set<GHEvent> events() {
        return immutableEnumSet(PULL_REQUEST);
    }

    /**
     * Handles Labels on pull requests.
     *
     * @param event   only label event
     * @param payload payload of gh-event. Never blank
     */
    @Override
    protected void onEvent(GHEvent event, String payload) {
        JSONObject json = JSONObject.fromObject(payload);

        // Make sure this issue is a PR
        //final String pullRequestUrl = json.getJSONObject("pull_request").getString("html_url");
        JSONObject pullRequest = json.getJSONObject("pull_request");
        final String pullRequestUrl = pullRequest.getString("html_url");
        int pullRequestId = pullRequest.getInt("number");
        String labellingAuthor = json.getJSONObject("sender").getString("login");
        LOGGER.fine(() -> String.format("PR Review Author: %s", labellingAuthor));

        // Make sure the action is labeled
        String action = json.getString("action");
        if (!ACTION_LABELED.equals(action)) {
            LOGGER.log(Level.FINER, "Event is not labeled ({0}) for PR {1}, ignoring",
                    new Object[]{action, pullRequestUrl}
            );
            return;
        }

        final String label = json.getJSONObject("label").getString("name");
        final String labelUrl = json.getJSONObject("label").getString("url");

        String repoUrl = getRepoUrl(json);
        final GitHubRepositoryName changedRepository = getChangedRepository(repoUrl);
        if (changedRepository == null) {
            return;
        }

        LOGGER.log(Level.FINE, "Received label on PR {0} for {1}", new Object[]{pullRequestId, repoUrl});
        checkAndRunJobs(changedRepository, pullRequestId, labellingAuthor, null,
                (job, branchProp) -> {
            String expectedLabel = branchProp.getLabel();
            Pattern pattern = Pattern.compile(expectedLabel,
                    Pattern.CASE_INSENSITIVE | Pattern.DOTALL);
            if (pattern.matcher(label).matches()) {
                return new GitHubPullRequestLabelCause(labelUrl, labellingAuthor, label);
            }
            LOGGER.log(Level.FINER,
                    "Label does not match the trigger build label string ({0}) for {1}",
                    new Object[]{expectedLabel, job.getFullName()}
            );
            return null;
        });
    }
}
