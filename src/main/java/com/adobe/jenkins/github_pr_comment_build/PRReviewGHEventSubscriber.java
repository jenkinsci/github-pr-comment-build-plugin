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
import static org.kohsuke.github.GHEvent.PULL_REQUEST_REVIEW;

/**
 * This subscriber manages {@link GHEvent} PULL_REQUEST_REVIEW edits.
 */
@Extension
public class PRReviewGHEventSubscriber extends BasePRGHEventSubscriber {
    /**
     * Logger.
     */
    private static final Logger LOGGER = Logger.getLogger(PRReviewGHEventSubscriber.class.getName());

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

        LOGGER.log(Level.FINE, "Received review on PR {1} for {2}", new Object[] { pullRequestId, repoUrl });
        AtomicBoolean jobFound = new AtomicBoolean(false);
        Set<Job<?, ?>> alreadyTriggeredJobs = new HashSet<>();
        forEachMatchingJob(changedRepository, pullRequestId, job -> {
            // The findHead method above only works for multibranch projects,
            // so no need to check the parent class
            boolean propFound = false;
            for (BranchProperty prop : ((MultiBranchProject) job.getParent()).getProjectFactory().
                    getBranch(job).getProperties()) {
                if (!(prop instanceof TriggerPRReviewBranchProperty branchProp)) {
                    continue;
                }
                if (!GithubHelper.isAuthorized(job, author, branchProp.getMinimumPermissions())) {
                    continue;
                }
                propFound = true;
                if (alreadyTriggeredJobs.add(job)) {
                    ParameterizedJobMixIn.scheduleBuild2(job, 0,
                            new CauseAction(new GitHubPullRequestReviewCause(author, pullRequestUrl)));
                } else {
                    LOGGER.log(Level.FINE, "Skipping already triggered job {0}", new Object[] { job });
                }
                break;
            }

            if (!propFound) {
                LOGGER.log(Level.FINE,
                        "Job {0} for {1}:{2}/{3} does not have a trigger PR review branch property",
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
            LOGGER.log(Level.FINE, "PR review on {0}:{1}/{2} did not match any job",
                    new Object[] {
                        changedRepository.getHost(), changedRepository.getUserName(),
                        changedRepository.getRepositoryName()
                    }
            );
        }
    }
}