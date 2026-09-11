package com.adobe.jenkins.github_pr_comment_build;

import com.cloudbees.jenkins.GitHubRepositoryName;
import hudson.model.Cause;
import hudson.model.Job;
import jenkins.branch.BranchProperty;
import jenkins.branch.BranchSource;
import jenkins.branch.DefaultBranchPropertyStrategy;
import jenkins.branch.MultiBranchProject;
import org.jenkinsci.plugins.github_branch_source.GitHubSCMSource;
import org.jenkinsci.plugins.workflow.multibranch.WorkflowMultiBranchProject;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.jvnet.hudson.test.JenkinsRule;
import org.jvnet.hudson.test.junit.jupiter.WithJenkins;

import java.util.HashSet;
import java.util.Set;
import java.util.function.BiFunction;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tests for the rescanOnMissingJob opt-in behavior added to {@link BasePRGHEventSubscriber}.
 *
 * <p>These deliberately avoid ever letting {@link BasePRGHEventSubscriber#requestRescanIfConfigured} actually
 * schedule a folder build - doing so against a real {@link GitHubSCMSource} would trigger genuine outbound
 * network calls to GitHub when the queued computation runs. Instead:
 * <ul>
 *     <li>The "which projects need a rescan" decision is tested directly via the package-private, side-effect
 *     -free {@link BasePRGHEventSubscriber#findProjectsNeedingRescan}.</li>
 *     <li>The retry-exhaustion behavior is tested by invoking the package-private
 *     {@link BasePRGHEventSubscriber#scheduleRetry} directly against a repository with no configured project
 *     at all, so the underlying match attempt fails fast with no network involved.</li>
 * </ul>
 */
@WithJenkins
class BasePRGHEventSubscriberRescanTest {

    private static JenkinsRule j;

    @BeforeAll
    static void setUp(JenkinsRule rule) {
        j = rule;
    }

    private final IssueLabelGHEventSubscriber subscriber = new IssueLabelGHEventSubscriber();

    @Test
    void findProjectsNeedingRescan_includesProject_whenFlagEnabled() throws Exception {
        TriggerPRLabelBranchProperty labelProp = new TriggerPRLabelBranchProperty("^my-label$");
        labelProp.setRescanOnMissingJob(true);
        WorkflowMultiBranchProject project = j.jenkins.createProject(WorkflowMultiBranchProject.class, "flag-enabled");
        project.getSourcesList().add(new BranchSource(
                new GitHubSCMSource("someOwner", "someRepo"),
                new DefaultBranchPropertyStrategy(new BranchProperty[] {labelProp})));

        GitHubRepositoryName repo = GitHubRepositoryName.create("https://github.com/someOwner/someRepo");
        Set<MultiBranchProject<?, ?>> result = subscriber.findProjectsNeedingRescan(repo);

        assertTrue(result.contains(project), "project with rescanOnMissingJob enabled should be selected for rescan");
    }

    @Test
    void findProjectsNeedingRescan_excludesProject_whenFlagDisabled() throws Exception {
        TriggerPRLabelBranchProperty labelProp = new TriggerPRLabelBranchProperty("^my-label$");
        // rescanOnMissingJob left at its default (false)
        WorkflowMultiBranchProject project = j.jenkins.createProject(WorkflowMultiBranchProject.class, "flag-disabled");
        project.getSourcesList().add(new BranchSource(
                new GitHubSCMSource("otherOwner", "otherRepo"),
                new DefaultBranchPropertyStrategy(new BranchProperty[] {labelProp})));

        GitHubRepositoryName repo = GitHubRepositoryName.create("https://github.com/otherOwner/otherRepo");
        Set<MultiBranchProject<?, ?>> result = subscriber.findProjectsNeedingRescan(repo);

        assertTrue(result.isEmpty(), "project with rescanOnMissingJob disabled (the default) should not be selected");
    }

    @Test
    void findProjectsNeedingRescan_excludesProject_whenRepositoryDoesNotMatch() throws Exception {
        TriggerPRLabelBranchProperty labelProp = new TriggerPRLabelBranchProperty("^my-label$");
        labelProp.setRescanOnMissingJob(true);
        WorkflowMultiBranchProject project = j.jenkins.createProject(WorkflowMultiBranchProject.class, "different-repo");
        project.getSourcesList().add(new BranchSource(
                new GitHubSCMSource("someOwner", "someRepo"),
                new DefaultBranchPropertyStrategy(new BranchProperty[] {labelProp})));

        GitHubRepositoryName unrelatedRepo =
                GitHubRepositoryName.create("https://github.com/unrelatedOwner/unrelatedRepo");
        Set<MultiBranchProject<?, ?>> result = subscriber.findProjectsNeedingRescan(unrelatedRepo);

        assertTrue(result.isEmpty(), "a project for a different repository should never be selected");
    }

    @Test
    void scheduleRetry_exhaustsAfterConfiguredAttempts_whenMatchNeverSucceeds() throws Exception {
        long originalDelay = BasePRGHEventSubscriber.rescanRetryDelayMillis;
        BasePRGHEventSubscriber.rescanRetryDelayMillis = 100L;
        try {
            // No project is configured for this repository, so attemptMatch() fails fast on every attempt
            // with no network access - only the retry bookkeeping is under test here.
            GitHubRepositoryName repo =
                    GitHubRepositoryName.create("https://github.com/nonexistentOwner/nonexistentRepo");
            BiFunction<Job<?, ?>, TriggerPRLabelBranchProperty, Cause> neverCauses = (job, prop) -> null;

            int before = BasePRGHEventSubscriber.attemptMatchInvocations.get();
            subscriber.scheduleRetry(repo, 99999, "someone", null, neverCauses, new HashSet<>(), 3);

            // 3 attempts at 100ms apart should be done well within 1s.
            Thread.sleep(1000);
            int afterFirstWait = BasePRGHEventSubscriber.attemptMatchInvocations.get();
            assertEquals(3, afterFirstWait - before, "expected exactly 3 retry attempts, then stop");

            // Confirm it actually stopped, rather than just being slow to run the next one.
            Thread.sleep(500);
            assertEquals(afterFirstWait, BasePRGHEventSubscriber.attemptMatchInvocations.get(),
                    "retry loop should have stopped rather than continuing past the given attempt count");
        } finally {
            BasePRGHEventSubscriber.rescanRetryDelayMillis = originalDelay;
        }
    }
}
