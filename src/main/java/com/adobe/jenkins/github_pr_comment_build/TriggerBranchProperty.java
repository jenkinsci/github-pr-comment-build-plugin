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

    @Deprecated
    public boolean isAllowUntrusted() {
        return allowUntrusted;
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

