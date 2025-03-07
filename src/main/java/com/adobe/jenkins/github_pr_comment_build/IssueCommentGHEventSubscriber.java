package com.adobe.jenkins.github_pr_comment_build;

import java.io.IOException;
import java.io.StringReader;
import java.util.Set;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.regex.Pattern;

import com.cloudbees.jenkins.GitHubRepositoryName;
import hudson.Extension;
import hudson.model.Job;
import jenkins.scm.api.SCMSource;
import net.sf.json.JSONObject;
import org.kohsuke.github.GHEvent;
import org.kohsuke.github.GHEventPayload;
import org.kohsuke.github.GitHub;
import org.kohsuke.github.ReactionContent;

import static com.google.common.collect.Sets.immutableEnumSet;
import static org.kohsuke.github.GHEvent.ISSUE_COMMENT;

/**
 * This subscriber manages {@link org.kohsuke.github.GHEvent} ISSUE_COMMENT.
 */
@Extension
public class IssueCommentGHEventSubscriber extends BasePRGHEventSubscriber<TriggerPRCommentBranchProperty, String> {
    /**
     * Logger.
     */
    private static final Logger LOGGER = Logger.getLogger(IssueCommentGHEventSubscriber.class.getName());
    /**
     * String representing the created action on an issue comment.
     */
    private static final String ACTION_CREATED = "created";
    /**
     * String representing the edited action on an issue comment.
     */
    private static final String ACTION_EDITED = "edited";

    @Override
    protected Class<TriggerPRCommentBranchProperty> getTriggerClass() {
        return TriggerPRCommentBranchProperty.class;
    }

    @Override
    protected Set<GHEvent> events() {
        return immutableEnumSet(ISSUE_COMMENT);
    }

    private void reactToComment(final Job<?, ?> job, final String payload) {
        try {
            final SCMSource scmSource = SCMSource.SourceByItem.findSource(job);
            final GitHub gitHub = GithubHelper.getGitHub(scmSource, job);
            if (gitHub == null) {
                LOGGER.log(Level.WARNING, "Could not react to triggering comment, GitHub connection failed");
                return;
            }
            final GHEventPayload.IssueComment event = gitHub.parseEventPayload(
                    new StringReader(payload), GHEventPayload.IssueComment.class);
            event.getComment().createReaction(ReactionContent.PLUS_ONE);
            LOGGER.log(Level.FINE, "Added plus one reaction to comment {0}", event.getComment().getHtmlUrl());
        } catch (final IOException e) {
            LOGGER.log(Level.WARNING, "Could not react to triggering comment", e);
        }
    }

    @Override
    protected void postStartJob(TriggerPRCommentBranchProperty branchProp, Job<?, ?> job, String payload) {
        // Add reaction if configured
        if (branchProp.getAddReaction()) {
            reactToComment(job, payload);
        }
    }

    /**
     * Handles comments on pull requests.
     * @param event only ISSUE_COMMENT event
     * @param payload payload of gh-event. Never blank
     */
    @Override
    protected void onEvent(GHEvent event, String payload) {
        JSONObject json = JSONObject.fromObject(payload);
        JSONObject issueJson = json.getJSONObject("issue");

        // Make sure this issue is a PR
        final String issueUrl = issueJson.getString("html_url");
        if (!issueJson.containsKey("pull_request")) {
            LOGGER.log(Level.FINE, "Issue comment is not for a pull request, ignoring {0}", issueUrl);
            return;
        }
        final int pullRequestId = issueJson.getInt("number");

        // Verify that the comment body matches the trigger build string
        final String commentBody = json.getJSONObject("comment").getString("body");
        final String commentAuthor = json.getJSONObject("comment").getJSONObject("user").getString("login");
        final String commentUrl = json.getJSONObject("comment").getString("html_url");

        // Make sure the action is edited or created (not deleted)
        String action = json.getString("action");
        if (!ACTION_CREATED.equals(action) && !ACTION_EDITED.equals(action)) {
            LOGGER.log(Level.FINER, "Issue comment action is not created or edited ({0}) for PR {1}",
                    new Object[] { action, issueUrl }
            );
            return;
        }

        String repoUrl = getRepoUrl(json);
        final GitHubRepositoryName changedRepository = getChangedRepository(repoUrl);
        if (changedRepository == null) {
            return;
        }

        LOGGER.log(Level.FINE, "Received comment on PR {0} for {1}", new Object[] { pullRequestId, repoUrl });
        checkAndRunJobs(changedRepository, pullRequestId, commentAuthor, payload,
                (job,branchProp) -> {
            String expectedCommentBody = branchProp.getCommentBody();
            Pattern pattern = Pattern.compile(expectedCommentBody,
                    Pattern.CASE_INSENSITIVE | Pattern.DOTALL);
            if (commentBody == null || pattern.matcher(commentBody).matches()) {
                // Comment matches, return a cause to trigger the job to start
                return new GitHubPullRequestCommentCause(commentUrl, commentAuthor, commentBody);
            }
            LOGGER.log(Level.FINER,
                    "Issue comment does not match the trigger build string ({0}) for {1}",
                    new Object[] { expectedCommentBody, job.getFullName() }
            );
            return null;
        });
    }
}
