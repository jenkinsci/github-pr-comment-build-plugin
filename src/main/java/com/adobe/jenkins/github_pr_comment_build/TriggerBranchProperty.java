package com.adobe.jenkins.github_pr_comment_build;

import hudson.model.Job;
import hudson.model.Run;
import jenkins.branch.BranchProperty;
import jenkins.branch.JobDecorator;
import org.kohsuke.stapler.DataBoundSetter;

/**
 * Common parts of TriggerPR*BranchProperty classes
 */
abstract public class TriggerBranchProperty extends BranchProperty {
    protected boolean allowUntrusted;
    protected String minimumPermissions;
    /**
     * When true, and this event did not match any existing job, request a rescan of the owning
     * multibranch project (to pick up a PR whose job was not yet indexed) and retry the match a
     * few times in the background. Defaults to false so existing installs see no behavior change.
     */
    protected boolean rescanOnMissingJob;

    @Deprecated
    public boolean isAllowUntrusted() {
        return allowUntrusted;
    }

    /**
     * Whether a missing job match should trigger a rescan-and-retry.
     *
     * @return true if a rescan-and-retry should be attempted when no job is found
     */
    public boolean isRescanOnMissingJob() {
        return rescanOnMissingJob;
    }

    @DataBoundSetter
    public void setRescanOnMissingJob(boolean rescanOnMissingJob) {
        this.rescanOnMissingJob = rescanOnMissingJob;
    }

    @DataBoundSetter
    @Deprecated
    public void setAllowUntrusted(boolean allowUntrusted) {
        this.allowUntrusted = allowUntrusted;
    }

    @DataBoundSetter
    public void setMinimumPermissions(String minimumPermissions) {
        this.minimumPermissions = minimumPermissions;
    }

    public String getMinimumPermissions() {
        if (minimumPermissions == null || minimumPermissions.isEmpty()) {
            return this.allowUntrusted ? "NONE" : "WRITE";
        }
        return minimumPermissions;
    }

    @Override
    public <P extends Job<P, B>, B extends Run<P, B>> JobDecorator<P, B> jobDecorator(Class<P> clazz) {
        return null;
    }
}

