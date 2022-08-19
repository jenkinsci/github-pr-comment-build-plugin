package com.adobe.jenkins.github_pr_comment_build;

import com.cloudbees.jenkins.GitHubRepositoryName;
import hudson.Extension;
import hudson.model.CauseAction;
import hudson.model.Item;
import hudson.model.Job;
import hudson.security.ACL;
import jenkins.branch.BranchProperty;
import jenkins.branch.MultiBranchProject;
import jenkins.model.ParameterizedJobMixIn;
import jenkins.scm.api.SCMSource;
import jenkins.scm.api.SCMSourceOwner;
import jenkins.scm.api.SCMSourceOwners;
import net.sf.json.JSONObject;
import org.jenkinsci.plugins.github.extension.GHEventsSubscriber;
import org.jenkinsci.plugins.github_branch_source.GitHubSCMSource;
import org.kohsuke.github.GHEvent;

import java.util.Set;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static com.google.common.collect.Sets.immutableEnumSet;
import static org.kohsuke.github.GHEvent.PULL_REQUEST_REVIEW;

/**
 * This subscriber manages {@link GHEvent} PULL_REQUEST_REVIEW edits.
 */
@Extension
public class PRReviewGHEventSubscriber extends GHEventsSubscriber {
    /**
     * Logger.
     */
    private static final Logger LOGGER = Logger.getLogger(PRReviewGHEventSubscriber.class.getName());
    /**
     * Regex pattern for a GitHub repository.
     */
    private static final Pattern REPOSITORY_NAME_PATTERN = Pattern.compile("https?://([^/]+)/([^/]+)/([^/]+)");

    @Override
    protected boolean isApplicable(Item item) {
        if (item != null && item instanceof Job<?, ?>) {
            Job<?, ?> project = (Job<?, ?>) item;
            if (project.getParent() instanceof SCMSourceOwner) {
                SCMSourceOwner owner = (SCMSourceOwner) project.getParent();
                for (SCMSource source : owner.getSCMSources()) {
                    if (source instanceof GitHubSCMSource) {
                        return true;
                    }
                }
            }
        }
        return false;
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
        Integer pullRequestId = pullRequest.getInt("number");
        String author = json.getJSONObject("sender").getString("login");
        LOGGER.fine(() -> String.format("PR Review Author: %s", author));

        // Set some values used below
        final Pattern pullRequestJobNamePattern = Pattern.compile("^PR-" + pullRequestId + "\\b.*$",
                Pattern.CASE_INSENSITIVE);

        // Make sure the repository URL is valid
        String repoUrl = json.getJSONObject("repository").getString("html_url");
        Matcher matcher = REPOSITORY_NAME_PATTERN.matcher(repoUrl);
        if (!matcher.matches()) {
            LOGGER.log(Level.WARNING, "Malformed repository URL {0}", repoUrl);
            return;
        }
        final GitHubRepositoryName changedRepository = GitHubRepositoryName.create(repoUrl);
        if (changedRepository == null) {
            LOGGER.log(Level.WARNING, "Malformed repository URL {0}", repoUrl);
            return;
        }

        LOGGER.log(Level.FINE, "Received review on PR {1} for {2}", new Object[] { pullRequestId, repoUrl });
        ACL.impersonate(ACL.SYSTEM, new Runnable() {
            @Override
            public void run() {
                boolean jobFound = false;
                topLevel:
                for (final SCMSourceOwner owner : SCMSourceOwners.all()) {
                    for (SCMSource source : owner.getSCMSources()) {
                        if (!(source instanceof GitHubSCMSource)) {
                            continue;
                        }
                        GitHubSCMSource gitHubSCMSource = (GitHubSCMSource) source;
                        if (gitHubSCMSource.getRepoOwner().equalsIgnoreCase(changedRepository.getUserName()) &&
                                gitHubSCMSource.getRepository().equalsIgnoreCase(changedRepository.getRepositoryName())) {
                            for (Job<?, ?> job : owner.getAllJobs()) {
                                if (pullRequestJobNamePattern.matcher(job.getName()).matches()) {
                                    if (!(job.getParent() instanceof MultiBranchProject)) {
                                        continue;
                                    }
                                    boolean propFound = false;
                                    for (BranchProperty prop : ((MultiBranchProject) job.getParent()).getProjectFactory().
                                            getBranch(job).getProperties()) {
                                        if (!(prop instanceof TriggerPRReviewBranchProperty)) {
                                            continue;
                                        }
                                        TriggerPRReviewBranchProperty branchProp = (TriggerPRReviewBranchProperty)prop;
                                        if (!branchProp.isAllowUntrusted() && !GithubHelper.isAuthorized(job, author)) {
                                            continue;
                                        }
                                        propFound = true;
                                        ParameterizedJobMixIn.scheduleBuild2(job, 0,
                                                new CauseAction(new GitHubPullRequestReviewCause(pullRequestUrl)));
                                        if (branchProp.isAllowMultiple()) {
                                            break;
                                        } else {
                                            break topLevel;
                                        }
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

                                    jobFound = true;
                                }
                            }
                        }
                    }
                }
                if (!jobFound) {
                    LOGGER.log(Level.FINE, "PR review on {0}:{1}/{2} did not match any job",
                            new Object[] {
                                    changedRepository.getHost(), changedRepository.getUserName(),
                                    changedRepository.getRepositoryName()
                            }
                    );
                }
            }
        });
    }
}