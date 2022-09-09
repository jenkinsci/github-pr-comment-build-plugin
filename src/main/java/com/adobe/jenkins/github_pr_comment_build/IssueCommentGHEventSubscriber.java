package com.adobe.jenkins.github_pr_comment_build;

import java.util.Set;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

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

import static com.google.common.collect.Sets.immutableEnumSet;
import static org.kohsuke.github.GHEvent.ISSUE_COMMENT;

/**
 * This subscriber manages {@link org.kohsuke.github.GHEvent} ISSUE_COMMENT.
 */
@Extension
public class IssueCommentGHEventSubscriber extends GHEventsSubscriber {
    /**
     * Logger.
     */
    private static final Logger LOGGER = Logger.getLogger(IssueCommentGHEventSubscriber.class.getName());
    /**
     * Regex pattern for a GitHub repository.
     */
    private static final Pattern REPOSITORY_NAME_PATTERN = Pattern.compile("https?://([^/]+)/([^/]+)/([^/]+)");
    /**
     * Regex pattern for a pull request ID.
     */
    private static final Pattern PULL_REQUEST_ID_PATTERN = Pattern.compile("https?://[^/]+/[^/]+/[^/]+/pull/(\\d+)");
    /**
     * String representing the created action on an issue comment.
     */
    private static final String ACTION_CREATED = "created";
    /**
     * String representing the edited action on an issue comment.
     */
    private static final String ACTION_EDITED = "edited";

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

        // Make sure this issue is a PR
        final String issueUrl = json.getJSONObject("issue").getString("html_url");
        Matcher matcher = PULL_REQUEST_ID_PATTERN.matcher(issueUrl);
        if (!matcher.matches()) {
            LOGGER.log(Level.FINE, "Issue comment is not for a pull request, ignoring {0}", issueUrl);
            return;
        }

        final String pullRequestId = matcher.group(1);
        final Pattern pullRequestJobNamePattern = Pattern.compile("^PR-" + pullRequestId + "\\b.*$", Pattern.CASE_INSENSITIVE);

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

        // Make sure the repository URL is valid
        String repoUrl = json.getJSONObject("repository").getString("html_url");
        matcher = REPOSITORY_NAME_PATTERN.matcher(repoUrl);
        if (!matcher.matches()) {
            LOGGER.log(Level.WARNING, "Malformed repository URL {0}", repoUrl);
            return;
        }
        final GitHubRepositoryName changedRepository = GitHubRepositoryName.create(repoUrl);
        if (changedRepository == null) {
            LOGGER.log(Level.WARNING, "Malformed repository URL {0}", repoUrl);
            return;
        }

        LOGGER.log(Level.FINE, "Received comment on PR {0} for {1}", new Object[] { pullRequestId, repoUrl });
        ACL.impersonate(ACL.SYSTEM, new Runnable() {
            @Override
            public void run() {
                boolean jobFound = false;
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
                                        if (!(prop instanceof TriggerPRCommentBranchProperty)) {
                                            continue;
                                        }
                                        propFound = true;
                                        TriggerPRCommentBranchProperty branchProp = (TriggerPRCommentBranchProperty)prop;
                                        String expectedCommentBody = branchProp.getCommentBody();
                                        if (!branchProp.isAllowUntrusted() && !GithubHelper.isAuthorized(job, commentAuthor)) {
                                            continue;
                                        }
                                        Pattern pattern = Pattern.compile(expectedCommentBody,
                                                Pattern.CASE_INSENSITIVE | Pattern.DOTALL);
                                        if (commentBody == null || pattern.matcher(commentBody).matches()) {
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

                                    jobFound = true;
                                }
                            }
                        }
                    }
                }
                if (!jobFound) {
                    LOGGER.log(Level.FINE, "PR comment on {0}:{1}/{2} did not match any job",
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
