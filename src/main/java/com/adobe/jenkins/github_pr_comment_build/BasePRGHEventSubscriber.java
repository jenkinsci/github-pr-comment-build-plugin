package com.adobe.jenkins.github_pr_comment_build;

import com.cloudbees.jenkins.GitHubRepositoryName;
import hudson.model.Cause;
import hudson.model.CauseAction;
import hudson.model.Item;
import hudson.model.Job;
import hudson.security.ACL;
import hudson.security.ACLContext;
import jenkins.branch.BranchProperty;
import jenkins.branch.MultiBranchProject;
import jenkins.branch.OrganizationFolder;
import jenkins.model.ParameterizedJobMixIn;
import jenkins.scm.api.SCMHead;
import jenkins.scm.api.SCMSource;
import jenkins.scm.api.SCMSourceOwner;
import jenkins.scm.api.SCMSourceOwners;
import net.sf.json.JSONObject;
import org.jenkinsci.plugins.github.extension.GHEventsSubscriber;
import org.jenkinsci.plugins.github_branch_source.GitHubSCMSource;
import org.jenkinsci.plugins.github_branch_source.PullRequestSCMHead;

import java.util.HashSet;
import java.util.Set;
import java.util.function.BiFunction;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static hudson.security.ACL.as;

/**
 * Base subscriber for PR events.
 */
public abstract class BasePRGHEventSubscriber<T extends TriggerBranchProperty, U> extends GHEventsSubscriber {
    /**
     * Logger.
     */
    protected static final Logger LOGGER = Logger.getLogger(BasePRGHEventSubscriber.class.getName());
    /**
     * Regex pattern for a GitHub repository.
     */
    protected static final Pattern REPOSITORY_NAME_PATTERN = Pattern.compile("https?://([^/]+)/([^/]+)/([^/]+)");

    protected abstract Class<T> getTriggerClass();

    @Override
    protected boolean isApplicable(Item item) {
        if (item instanceof Job<?, ?> project) {
            if (project.getParent() instanceof SCMSourceOwner owner) {
                for (SCMSource source : owner.getSCMSources()) {
                    if (source instanceof GitHubSCMSource) {
                        return true;
                    }
                }
            }
        }
        return false;
    }

    protected String getRepoUrl(JSONObject json) {
        return json.getJSONObject("repository").getString("html_url");
    }

    protected GitHubRepositoryName getChangedRepository(String repoUrl) {
        Matcher matcher = REPOSITORY_NAME_PATTERN.matcher(repoUrl);
        if (!matcher.matches()) {
            LOGGER.log(Level.WARNING, "Malformed repository URL {0}", repoUrl);
            return null;
        }
        final GitHubRepositoryName changedRepository = GitHubRepositoryName.create(repoUrl);
        if (changedRepository == null) {
            LOGGER.log(Level.WARNING, "Malformed repository URL {0}", repoUrl);
            return null;
        }
        return changedRepository;
    }

    /**
     * Called after a job is started successfully, may be used for adding reactions or performing other actions.
     * @param branchProp the branch property
     * @param job the job
     * @param postStartParam an arbitrary parameter
     */
    protected void postStartJob(T branchProp, Job<?, ?> job, U postStartParam) {
        // no-op
    }

    protected void checkAndRunJobs(GitHubRepositoryName changedRepository, int pullRequestId, String author,
                                   U postStartParam, BiFunction<Job<?, ?>, T, Cause> getCauseFunction) {
        try (ACLContext aclContext = as(ACL.SYSTEM)) {
            boolean jobFound = false;
            Set<Job<?, ?>> alreadyTriggeredJobs = new HashSet<>();
            for (final SCMSourceOwner owner : SCMSourceOwners.all()) {
                for (SCMSource source : owner.getSCMSources()) {
                    if (!(source instanceof GitHubSCMSource gitHubSCMSource)) {
                        continue;
                    }
                    if (gitHubSCMSource.getRepoOwner().equalsIgnoreCase(changedRepository.getUserName()) &&
                            gitHubSCMSource.getRepository().equalsIgnoreCase(changedRepository.getRepositoryName())) {
                        OrganizationFolder orgFolder = owner instanceof OrganizationFolder ? (OrganizationFolder) owner : null;
                        for (Job<?, ?> job : owner.getAllJobs()) {
                            if (orgFolder != null) {
                                if (SCMSource.SourceByItem.findSource(job) == source) {
                                    LOGGER.log(Level.FINE,
                                            "SCM owner is an organization folder and SCM source for job {0} matches",
                                            job.getFullName());
                                } else {
                                    continue;
                                }
                            }
                            if (SCMHead.HeadByItem.findHead(job) instanceof PullRequestSCMHead prHead &&
                                    prHead.getNumber() == pullRequestId) {
                                boolean propFound = false;
                                for (BranchProperty prop : ((MultiBranchProject) job.getParent()).getProjectFactory().
                                        getBranch(job).getProperties()) {
                                    if (!(getTriggerClass().isAssignableFrom(prop.getClass()))) {
                                        continue;
                                    }
                                    T branchProp = getTriggerClass().cast(prop);
                                    propFound = true;
                                    if (!GithubHelper.isAuthorized(job, author, branchProp.getMinimumPermissions())) {
                                        continue;
                                    }
                                    Cause cause = getCauseFunction.apply(job, branchProp);
                                    if (cause == null) {
                                        // Do not trigger the job
                                        continue;
                                    }
                                    if (alreadyTriggeredJobs.add(job)) {
                                        ParameterizedJobMixIn.scheduleBuild2(job, 0, new CauseAction(cause));
                                        LOGGER.log(Level.FINE,
                                                "Triggered build for {0} due to PR event on {1}:{2}/{3}",
                                                new Object[] {
                                                        job.getFullName(),
                                                        changedRepository.getHost(),
                                                        changedRepository.getUserName(),
                                                        changedRepository.getRepositoryName()
                                                }
                                        );
                                        postStartJob(branchProp, job, postStartParam);
                                    } else {
                                        LOGGER.log(Level.FINE, "Skipping already triggered job {0}", new Object[] { job.getFullName() });
                                    }
                                    break;
                                }

                                if (!propFound) {
                                    LOGGER.log(Level.FINE,
                                            "Job {0} for {1}:{2}/{3} does not have a branch property of type {4}",
                                            new Object[] {
                                                    job.getFullName(),
                                                    changedRepository.getHost(),
                                                    changedRepository.getUserName(),
                                                    changedRepository.getRepositoryName(),
                                                    getTriggerClass().getSimpleName()
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
                LOGGER.log(Level.FINE, "PR event on {0}:{1}/{2} did not match any job",
                        new Object[] {
                                changedRepository.getHost(), changedRepository.getUserName(),
                                changedRepository.getRepositoryName()
                        }
                );
            }
        }
    }
}
