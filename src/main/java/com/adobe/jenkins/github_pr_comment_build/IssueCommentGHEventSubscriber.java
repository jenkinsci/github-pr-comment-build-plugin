package com.adobe.jenkins.github_pr_comment_build;

import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.regex.Pattern;

import com.cloudbees.jenkins.GitHubRepositoryName;
import hudson.Extension;
import hudson.model.CauseAction;
import hudson.model.Job;
import jenkins.branch.BranchProperty;
import jenkins.branch.MultiBranchProject;
import jenkins.model.ParameterizedJobMixIn;
import net.sf.json.JSONObject;
import org.kohsuke.github.GHEvent;

import static com.google.common.collect.Sets.immutableEnumSet;
import java.util.HashSet;
import static org.kohsuke.github.GHEvent.ISSUE_COMMENT;

/**
 * This subscriber manages {@link org.kohsuke.github.GHEvent} ISSUE_COMMENT.
 */
@Extension
public class IssueCommentGHEventSubscriber extends BasePRGHEventSubscriber {
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
    protected Set<GHEvent> events() {
        return immutableEnumSet(ISSUE_COMMENT);
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
        AtomicBoolean jobFound = new AtomicBoolean(false);
        Set<Job<?, ?>> alreadyTriggeredJobs = new HashSet<>();
        forEachMatchingJob(changedRepository, pullRequestId, job -> {
            boolean propFound = false;
            for (BranchProperty prop : ((MultiBranchProject) job.getParent()).getProjectFactory().
                    getBranch(job).getProperties()) {
                if (!(prop instanceof TriggerPRCommentBranchProperty branchProp)) {
                    continue;
                }
                propFound = true;
                String expectedCommentBody = branchProp.getCommentBody();
                if (!GithubHelper.isAuthorized(job, commentAuthor, branchProp.getMinimumPermissions())) {
                    continue;
                }
                Pattern pattern = Pattern.compile(expectedCommentBody,
                        Pattern.CASE_INSENSITIVE | Pattern.DOTALL);
                if (commentBody == null || pattern.matcher(commentBody).matches()) {
                    if (alreadyTriggeredJobs.add(job)) {
                        ParameterizedJobMixIn.scheduleBuild2(job, 0,
                                new CauseAction(new GitHubPullRequestCommentCause(
                                        commentUrl, commentAuthor, commentBody)));
                        LOGGER.log(Level.FINE,
                                "Triggered build for {0} due to PR comment on {1}:{2}/{3}",
                                new Object[] {
                                        job.getFullName(),
                                        changedRepository.getHost(),
                                        changedRepository.getUserName(),
                                        changedRepository.getRepositoryName()
                                }
                        );
                    } else {
                        LOGGER.log(Level.FINE, "Skipping already triggered job {0}", new Object[] { job });
                    }
                } else {
                    LOGGER.log(Level.FINER,
                            "Issue comment does not match the trigger build string ({0}) for {1}",
                            new Object[] { expectedCommentBody, job.getFullName() }
                    );
                }
                break;
            }

            if (!propFound) {
                LOGGER.log(Level.FINE,
                        "Job {0} for {1}:{2}/{3} does not have a trigger PR comment branch property",
                        new Object[] {
                                job.getFullName(),
                                changedRepository.getHost(),
                                changedRepository.getUserName(),
                                changedRepository.getRepositoryName()
                        }
                );
            }

            jobFound.set(true);
        });
        if (!jobFound.get()) {
            LOGGER.log(Level.FINE, "PR comment on {0}:{1}/{2} did not match any job",
                    new Object[] {
                        changedRepository.getHost(), changedRepository.getUserName(),
                        changedRepository.getRepositoryName()
                    }
            );
        }
    }
}
