package com.adobe.jenkins.github_pr_comment_build;

import edu.umd.cs.findbugs.annotations.NonNull;
import hudson.util.ListBoxModel;
import jenkins.branch.BranchPropertyDescriptor;
import org.kohsuke.accmod.Restricted;
import org.kohsuke.accmod.restrictions.NoExternalUse;
import org.kohsuke.github.GHPermissionType;

abstract public class TriggerBranchPropertyDescriptorImpl extends BranchPropertyDescriptor {

    /**
     * Populates the minimum permissions options.
     *
     * @return the minimum permissions options.
     */
    @NonNull
    @Restricted(NoExternalUse.class)
    @SuppressWarnings("unused") // stapler
    public ListBoxModel doFillMinimumPermissionsItems() {
        ListBoxModel result = new ListBoxModel();
        result.add("Only users with admin permission", String.valueOf(GHPermissionType.ADMIN));
        result.add("Only users that can push to the repository", String.valueOf(GHPermissionType.WRITE));
        result.add("Allow untrusted users to trigger the build", String.valueOf(GHPermissionType.NONE));
        return result;
    }

}
