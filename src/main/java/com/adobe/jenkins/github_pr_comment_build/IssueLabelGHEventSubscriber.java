package com.adobe.jenkins.github_pr_comment_build;

import com.cloudbees.jenkins.GitHubRepositoryName;
import hudson.Extension;
import hudson.model.CauseAction;
import hudson.model.Item;
import hudson.model.Job;
import hudson.security.ACL;
import hudson.security.ACLContext;
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

import java.util.HashSet;
import java.util.Set;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static com.google.common.collect.Sets.immutableEnumSet;
import static hudson.security.ACL.as;
import static org.kohsuke.github.GHEvent.PULL_REQUEST;

/**
 * This subscriber manages {@link GHEvent} Label.
 */
@Extension
public class IssueLabelGHEventSubscriber extends GHEventsSubscriber {
    /**
     * Logger.
     */
    private static final Logger LOGGER = Logger.getLogger(IssueLabelGHEventSubscriber.class.getName());
    /**
     * Regex pattern for a GitHub repository.
     */
    private static final Pattern REPOSITORY_NAME_PATTERN = Pattern.compile("https?://([^/]+)/([^/]+)/([^/]+)");
    /**
     * Regex pattern for a pull request ID.
     */
    private static final Pattern PULL_REQUEST_ID_PATTERN = Pattern.compile("https?://[^/]+/[^/]+/[^/]+/pull/(\\d+)");

    /**
     * String representing the created action on labeled PR.
     */
    private static final String ACTION_LABELED = "labeled";

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
        Integer pullRequestId = pullRequest.getInt("number");
        String labellingAuthor = json.getJSONObject("sender").getString("login");
        LOGGER.fine(() -> String.format("PR Review Author: %s", labellingAuthor));

        final Pattern pullRequestJobNamePattern = Pattern.compile("^PR-" + pullRequestId + "\\b.*$", Pattern.CASE_INSENSITIVE);

        final String label = json.getJSONObject("label").getString("name");
        final String labelUrl = json.getJSONObject("label").getString("url");

        // Make sure the action is edited or created (not deleted)
        String action = json.getString("action");
        if (!ACTION_LABELED.equals(action)) {
            LOGGER.log(Level.FINER, "Event is labeled ({0}) for PR {1}",
                    new Object[]{action, pullRequestUrl}
            );
            return;
        }

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

        LOGGER.log(Level.FINE, "Received label on PR {0} for {1}", new Object[]{pullRequestId, repoUrl});
        try (ACLContext aclContext = as(ACL.SYSTEM)) {
            boolean jobFound = false;
            Set<Job<?, ?>> alreadyTriggeredJobs = new HashSet<>();
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
                                    if (!(prop instanceof TriggerPRLabelBranchProperty)) {
                                        continue;
                                    }
                                    propFound = true;
                                    TriggerPRLabelBranchProperty branchProp = (TriggerPRLabelBranchProperty) prop;
                                    String expectedLabel = branchProp.getLabel();
                                    Pattern pattern = Pattern.compile(expectedLabel,
                                            Pattern.CASE_INSENSITIVE | Pattern.DOTALL);
                                    if (pattern.matcher(label).matches()) {
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

                                jobFound = true;
                            } else { // failed to match 'pullRequestJobNamePattern'
                                LOGGER.log(Level.FINE,
                                        "Skipping job [{0}] as it does not match the 'PR-' pattern." +
                                                "If this is unexpected, make sure the job is configured with a 'discover pull requests...' behavior (see README)",
                                        new Object[]{job.getName()});
                            }
                        }
                    }
                }
            }
            if (!jobFound) {
                LOGGER.log(Level.FINE, "PR label on {0}:{1}/{2} did not match any job",
                        new Object[]{
                                changedRepository.getHost(), changedRepository.getUserName(),
                                changedRepository.getRepositoryName()
                        }
                );
            }
        }
    }
}
