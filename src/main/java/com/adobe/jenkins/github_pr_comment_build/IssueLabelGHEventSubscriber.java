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

import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.regex.Pattern;

import static com.google.common.collect.Sets.immutableEnumSet;
import static org.kohsuke.github.GHEvent.PULL_REQUEST;

/**
 * This subscriber manages {@link GHEvent} Label.
 */
@Extension
public class IssueLabelGHEventSubscriber extends BasePRGHEventSubscriber {
    /**
     * Logger.
     */
    private static final Logger LOGGER = Logger.getLogger(IssueLabelGHEventSubscriber.class.getName());

    /**
     * String representing the created action on labeled PR.
     */
    private static final String ACTION_LABELED = "labeled";

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
        AtomicBoolean jobFound = new AtomicBoolean(false);
        Set<Job<?, ?>> alreadyTriggeredJobs = new HashSet<>();
        forEachMatchingJob(changedRepository, pullRequestId, job -> {
            // The findHead method above only works for multibranch projects,
            // so no need to check the parent class
            boolean propFound = false;
            for (BranchProperty prop : ((MultiBranchProject) job.getParent()).getProjectFactory().
                    getBranch(job).getProperties()) {
                if (!(prop instanceof TriggerPRLabelBranchProperty branchProp)) {
                    continue;
                }
                propFound = true;
                String expectedLabel = branchProp.getLabel();
                Pattern pattern = Pattern.compile(expectedLabel,
                        Pattern.CASE_INSENSITIVE | Pattern.DOTALL);
                if (pattern.matcher(label).matches()) {
                    if (!GithubHelper.isAuthorized(job, labellingAuthor, branchProp.getMinimumPermissions())) {
                        continue;
                    }
                    if (alreadyTriggeredJobs.add(job)) {
                        ParameterizedJobMixIn.scheduleBuild2(job, 0,
                                new CauseAction(new GitHubPullRequestLabelCause(
                                        labelUrl, labellingAuthor, label)));
                        LOGGER.log(Level.FINE,
                                "Triggered build for {0} due to PR Label on {1}:{2}/{3}",
                                new Object[]{
                                        job.getFullName(),
                                        changedRepository.getHost(),
                                        changedRepository.getUserName(),
                                        changedRepository.getRepositoryName()
                                }
                        );
                    } else {
                        LOGGER.log(Level.FINE, "Skipping already triggered job {0}", new Object[]{job});
                    }
                } else {
                    LOGGER.log(Level.FINER,
                            "Label does not match the trigger build label string ({0}) for {1}",
                            new Object[]{expectedLabel, job.getFullName()}
                    );
                }
                break;
            }

            if (!propFound) {
                LOGGER.log(Level.FINE,
                        "Job {0} for {1}:{2}/{3} does not have a trigger PR Label branch property",
                        new Object[]{
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
            LOGGER.log(Level.FINE, "PR label on {0}:{1}/{2} did not match any job",
                    new Object[]{
                            changedRepository.getHost(), changedRepository.getUserName(),
                            changedRepository.getRepositoryName()
                    }
            );
        }
    }
}
