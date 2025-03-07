package com.adobe.jenkins.github_pr_comment_build;

import com.cloudbees.jenkins.GitHubRepositoryName;
import hudson.Extension;
import hudson.model.CauseAction;
import hudson.model.Job;
import jenkins.branch.BranchProperty;
import jenkins.branch.MultiBranchProject;
import jenkins.model.ParameterizedJobMixIn;
import net.sf.json.JSONObject;
import org.kohsuke.github.GHEvent;

import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.logging.Level;
import java.util.logging.Logger;

import static com.google.common.collect.Sets.immutableEnumSet;
import java.util.HashSet;
import static org.kohsuke.github.GHEvent.PULL_REQUEST;

/**
 * This subscriber manages {@link GHEvent} PULL_REQUEST edits.
 */
@Extension
public class PRUpdateGHEventSubscriber extends BasePRGHEventSubscriber {
    /**
     * Logger.
     */
    private static final Logger LOGGER = Logger.getLogger(PRUpdateGHEventSubscriber.class.getName());
    /**
     * String representing the edited action on a pull request.
     */
    private static final String ACTION_EDITED = "edited";


    @Override
    protected Set<GHEvent> events() {
        return immutableEnumSet(PULL_REQUEST);
    }

    /**
     * Handles updates of pull requests.
     * @param event only PULL_REQUEST events
     * @param payload payload of gh-event. Never blank
     */
    @Override
    protected void onEvent(GHEvent event, String payload) {
        JSONObject json = JSONObject.fromObject(payload);

        // Since we receive both pull request and issue comment events in this same code,
        //  we check first which one it is and set values from different fields
        JSONObject pullRequest = json.getJSONObject("pull_request");
        final String pullRequestUrl = pullRequest.getString("html_url");
        int pullRequestId = pullRequest.getInt("number");
        String author = json.getJSONObject("sender").getString("login");
        LOGGER.fine(() -> String.format("PR Update Author: %s", author));

        // Make sure the action is edited
        String action = json.getString("action");
        if (!ACTION_EDITED.equals(action)) {
            LOGGER.log(Level.FINER, "Pull request action is not edited ({0}) for PR {1}, ignoring",
                    new Object[] { action, pullRequestUrl }
            );
            return;
        }

        String repoUrl = getRepoUrl(json);
        final GitHubRepositoryName changedRepository = getChangedRepository(repoUrl);
        if (changedRepository == null) {
            return;
        }

        LOGGER.log(Level.FINE, "Received update on PR {0} for {1}", new Object[] { pullRequestId, repoUrl });
        AtomicBoolean jobFound = new AtomicBoolean(false);
        Set<Job<?, ?>> alreadyTriggeredJobs = new HashSet<>();
        forEachMatchingJob(changedRepository, pullRequestId, job -> {
            // The findHead method above only works for multibranch projects,
            // so no need to check the parent class
            boolean propFound = false;
            for (BranchProperty prop : ((MultiBranchProject) job.getParent()).getProjectFactory().
                    getBranch(job).getProperties()) {
                if (!(prop instanceof TriggerPRUpdateBranchProperty branchProp)) {
                    continue;
                }
                if (!GithubHelper.isAuthorized(job, author, branchProp.getMinimumPermissions())) {
                    continue;
                }
                propFound = true;
                if (alreadyTriggeredJobs.add(job)) {
                    ParameterizedJobMixIn.scheduleBuild2(job, 0,
                            new CauseAction(new GitHubPullRequestUpdateCause(author, pullRequestUrl)));
                } else {
                    LOGGER.log(Level.FINE, "Skipping already triggered job {0}", new Object[]{job});
                }
                break;
            }

            if (!propFound) {
                LOGGER.log(Level.FINE,
                        "Job {0} for {1}:{2}/{3} does not have a trigger PR update branch property",
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
            LOGGER.log(Level.FINE, "PR update on {0}:{1}/{2} did not match any job",
                    new Object[] {
                        changedRepository.getHost(), changedRepository.getUserName(),
                        changedRepository.getRepositoryName()
                    }
            );
        }
    }
}
