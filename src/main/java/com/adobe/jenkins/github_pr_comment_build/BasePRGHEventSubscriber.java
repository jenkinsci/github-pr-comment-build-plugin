package com.adobe.jenkins.github_pr_comment_build;

import com.cloudbees.hudson.plugins.folder.computed.ComputedFolder;
import com.cloudbees.jenkins.GitHubRepositoryName;
import hudson.model.Cause;
import hudson.model.CauseAction;
import hudson.model.Item;
import hudson.model.Job;
import hudson.security.ACL;
import hudson.security.ACLContext;
import jenkins.branch.BranchProperty;
import jenkins.branch.BranchPropertyStrategy;
import jenkins.branch.BranchSource;
import jenkins.branch.DefaultBranchPropertyStrategy;
import jenkins.branch.MultiBranchProject;
import jenkins.branch.OrganizationFolder;
import jenkins.model.ParameterizedJobMixIn;
import jenkins.scm.api.SCMHead;
import jenkins.scm.api.SCMSource;
import jenkins.scm.api.SCMSourceOwner;
import jenkins.scm.api.SCMSourceOwners;
import jenkins.util.Timer;
import net.sf.json.JSONObject;
import org.jenkinsci.plugins.github.extension.GHEventsSubscriber;
import org.jenkinsci.plugins.github_branch_source.GitHubSCMSource;
import org.jenkinsci.plugins.github_branch_source.PullRequestSCMHead;

import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
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

    /**
     * Number of times to retry the job match after requesting a rescan. 12 attempts at 10s each gives a
     * 2-minute window - observed indexing latency for a new PR ranged up to ~90s in manual testing, so this
     * leaves some margin rather than cutting it close.
     *
     * <p>Package-private and non-final (rather than a private constant) purely so tests can substitute a
     * much smaller window and observe the retry loop actually exhaust in a reasonable amount of test time.
     */
    static int rescanRetryAttempts = 12;
    /**
     * Delay before each rescan-retry attempt, giving the folder computation time to finish indexing. Retries
     * only re-check already-loaded in-memory state (no GitHub API calls), so a longer window here does not
     * add the repeated-rescan "traffic/churn" that opting into this flag is meant to avoid.
     *
     * <p>Package-private and non-final for the same test-tunability reason as {@link #rescanRetryAttempts}.
     */
    static long rescanRetryDelayMillis = 10000L;
    /**
     * Number of times {@link #attemptMatch} has been called, across all instances. Package-private purely for
     * tests to assert the retry loop ran (and stopped) the expected number of times; not used by production
     * logic.
     */
    static final AtomicInteger attemptMatchInvocations = new AtomicInteger();

    /**
     * {@code onEvent()} (and therefore this method) runs directly on the thread handling GitHub's webhook HTTP
     * request (see {@code GitHubWebHook.doIndex()} in github-plugin) - it must not block. The initial match
     * attempt is synchronous (cheap, in-memory), but the rescan-and-retry path is scheduled on
     * {@link Timer#get()} and returns immediately, so a slow indexing pass never holds up the webhook response
     * or ties up an HTTP-handling thread.
     */
    protected void checkAndRunJobs(GitHubRepositoryName changedRepository, int pullRequestId, String author,
                                   U postStartParam, BiFunction<Job<?, ?>, T, Cause> getCauseFunction) {
        try (ACLContext aclContext = as(ACL.SYSTEM)) {
            Set<Job<?, ?>> alreadyTriggeredJobs = new HashSet<>();
            boolean jobFound = attemptMatch(changedRepository, pullRequestId, author, postStartParam,
                    getCauseFunction, alreadyTriggeredJobs);

            if (!jobFound && requestRescanIfConfigured(changedRepository)) {
                LOGGER.log(Level.INFO,
                        "No job matched PR event on {0}:{1}/{2} yet; scheduling background rescan-and-retry",
                        new Object[] {
                                changedRepository.getHost(), changedRepository.getUserName(),
                                changedRepository.getRepositoryName()
                        }
                );
                scheduleRetry(changedRepository, pullRequestId, author, postStartParam, getCauseFunction,
                        alreadyTriggeredJobs, rescanRetryAttempts);
                return;
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

    /**
     * Schedules a single retry attempt on the shared Jenkins background timer, re-scheduling itself (rather
     * than blocking a thread with {@code Thread.sleep}) until either a job matches or attempts run out.
     *
     * <p>Package-private (rather than private) so tests can invoke the retry-and-exhaust behavior directly,
     * without needing a real, network-reachable {@code GitHubSCMSource} to drive it via
     * {@link #requestRescanIfConfigured}.
     */
    void scheduleRetry(GitHubRepositoryName changedRepository, int pullRequestId, String author,
                               U postStartParam, BiFunction<Job<?, ?>, T, Cause> getCauseFunction,
                               Set<Job<?, ?>> alreadyTriggeredJobs, int attemptsRemaining) {
        Timer.get().schedule(() -> {
            boolean jobFound;
            try (ACLContext aclContext = as(ACL.SYSTEM)) {
                jobFound = attemptMatch(changedRepository, pullRequestId, author, postStartParam,
                        getCauseFunction, alreadyTriggeredJobs);
            }
            if (jobFound) {
                LOGGER.log(Level.INFO, "PR event on {0}:{1}/{2} matched a job after rescan-and-retry",
                        new Object[] {
                                changedRepository.getHost(), changedRepository.getUserName(),
                                changedRepository.getRepositoryName()
                        }
                );
            } else if (attemptsRemaining > 1) {
                scheduleRetry(changedRepository, pullRequestId, author, postStartParam, getCauseFunction,
                        alreadyTriggeredJobs, attemptsRemaining - 1);
            } else {
                LOGGER.log(Level.FINE,
                        "PR event on {0}:{1}/{2} still did not match any job after rescan-and-retry",
                        new Object[] {
                                changedRepository.getHost(), changedRepository.getUserName(),
                                changedRepository.getRepositoryName()
                        }
                );
            }
        }, rescanRetryDelayMillis, TimeUnit.MILLISECONDS);
    }

    /**
     * Single pass over all known SCM source owners/jobs looking for a job matching the changed PR, triggering
     * a build for any match found. Must be called with {@code ACL.SYSTEM} already in effect.
     *
     * @return true if at least one job matching the PR was found (regardless of whether it was triggered)
     */
    private boolean attemptMatch(GitHubRepositoryName changedRepository, int pullRequestId, String author,
                                  U postStartParam, BiFunction<Job<?, ?>, T, Cause> getCauseFunction,
                                  Set<Job<?, ?>> alreadyTriggeredJobs) {
        attemptMatchInvocations.incrementAndGet();
        boolean jobFound = false;
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
        return jobFound;
    }

    /**
     * Looks for a {@link MultiBranchProject} matching the changed repository whose configured (project-level,
     * not per-job) branch property template has a {@link TriggerBranchProperty} of this subscriber's trigger
     * type with {@code rescanOnMissingJob} enabled. If found, schedules an immediate rescan of that project
     * so a not-yet-indexed PR job gets created without waiting for the next periodic scan.
     *
     * <p>Deliberately skips {@link OrganizationFolder} owners: rescanning an entire organization folder in
     * response to a single PR event would be far more expensive than the targeted case this is meant to help.
     * ({@link OrganizationFolder} does not extend {@link MultiBranchProject}, so this exclusion falls out of
     * the type check in {@link #findProjectsNeedingRescan} rather than needing special-case logic.)
     *
     * <p><b>Known limitation:</b> only recognizes the flag when the branch source uses
     * {@link DefaultBranchPropertyStrategy} (the common case, and what this was tested against). A project
     * using a different {@link BranchPropertyStrategy} (e.g. per-branch-name overrides) will not be detected
     * here even if the flag is set on it, and will fall back to the pre-existing (unfixed) behavior.
     *
     * @return true if a rescan was requested for at least one matching project
     */
    private boolean requestRescanIfConfigured(GitHubRepositoryName changedRepository) {
        Set<MultiBranchProject<?, ?>> toRescan = findProjectsNeedingRescan(changedRepository);
        for (MultiBranchProject<?, ?> project : toRescan) {
            LOGGER.log(Level.INFO, "Requesting rescan of {0} after PR event on {1}:{2}/{3} matched no job",
                    new Object[] {
                            project.getFullName(), changedRepository.getHost(),
                            changedRepository.getUserName(), changedRepository.getRepositoryName()
                    }
            );
            ((ComputedFolder<?>) project).scheduleBuild(0, new RescanCause());
        }
        return !toRescan.isEmpty();
    }

    /**
     * Pure lookup (no side effects - does not schedule anything) of {@link MultiBranchProject}s matching the
     * changed repository whose configured (project-level, not per-job) branch property template has a
     * {@link TriggerBranchProperty} of this subscriber's trigger type with {@code rescanOnMissingJob} enabled.
     *
     * <p>Split out from {@link #requestRescanIfConfigured} - and left package-private - specifically so tests
     * can verify this decision logic directly, without needing to observe a scheduled build (which would
     * otherwise require either real network access for the SCM source or Jenkins queue/timing assertions).
     *
     * @return the matching projects, empty if none
     */
    Set<MultiBranchProject<?, ?>> findProjectsNeedingRescan(GitHubRepositoryName changedRepository) {
        Set<MultiBranchProject<?, ?>> toRescan = new LinkedHashSet<>();
        for (final SCMSourceOwner owner : SCMSourceOwners.all()) {
            if (!(owner instanceof MultiBranchProject<?, ?> multiBranchProject)) {
                continue;
            }
            for (BranchSource branchSource : multiBranchProject.getSources()) {
                SCMSource source = branchSource.getSource();
                if (!(source instanceof GitHubSCMSource gitHubSCMSource)) {
                    continue;
                }
                if (!gitHubSCMSource.getRepoOwner().equalsIgnoreCase(changedRepository.getUserName()) ||
                        !gitHubSCMSource.getRepository().equalsIgnoreCase(changedRepository.getRepositoryName())) {
                    continue;
                }
                BranchPropertyStrategy strategy = branchSource.getStrategy();
                if (!(strategy instanceof DefaultBranchPropertyStrategy defaultStrategy)) {
                    continue;
                }
                for (BranchProperty prop : defaultStrategy.getProps()) {
                    if (getTriggerClass().isAssignableFrom(prop.getClass()) &&
                            getTriggerClass().cast(prop).isRescanOnMissingJob()) {
                        toRescan.add(multiBranchProject);
                        break;
                    }
                }
            }
        }
        return toRescan;
    }

    /**
     * Cause recorded against a folder-rescan build triggered because a PR event matched no known job.
     */
    private static class RescanCause extends Cause {
        @Override
        public String getShortDescription() {
            return "Rescan requested after a PR event matched no existing job (rescanOnMissingJob)";
        }
    }
}
