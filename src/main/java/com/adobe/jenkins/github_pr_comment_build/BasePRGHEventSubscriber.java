package com.adobe.jenkins.github_pr_comment_build;

import com.cloudbees.jenkins.GitHubRepositoryName;
import hudson.model.Item;
import hudson.model.Job;
import hudson.security.ACL;
import hudson.security.ACLContext;
import jenkins.scm.api.SCMHead;
import jenkins.scm.api.SCMSource;
import jenkins.scm.api.SCMSourceOwner;
import jenkins.scm.api.SCMSourceOwners;
import net.sf.json.JSONObject;
import org.jenkinsci.plugins.github.extension.GHEventsSubscriber;
import org.jenkinsci.plugins.github_branch_source.GitHubSCMSource;
import org.jenkinsci.plugins.github_branch_source.PullRequestSCMHead;

import java.util.function.Consumer;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static hudson.security.ACL.as;

/**
 * Base subscriber for PR events.
 */
public abstract class BasePRGHEventSubscriber extends GHEventsSubscriber {
    /**
     * Logger.
     */
    protected static final Logger LOGGER = Logger.getLogger(BasePRGHEventSubscriber.class.getName());
    /**
     * Regex pattern for a GitHub repository.
     */
    protected static final Pattern REPOSITORY_NAME_PATTERN = Pattern.compile("https?://([^/]+)/([^/]+)/([^/]+)");

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

    protected void forEachMatchingJob(GitHubRepositoryName changedRepository, int pullRequestId, Consumer<Job<?, ?>> jobConsumer) {
        try (ACLContext aclContext = as(ACL.SYSTEM)) {
            for (final SCMSourceOwner owner : SCMSourceOwners.all()) {
                for (SCMSource source : owner.getSCMSources()) {
                    if (!(source instanceof GitHubSCMSource gitHubSCMSource)) {
                        continue;
                    }
                    if (gitHubSCMSource.getRepoOwner().equalsIgnoreCase(changedRepository.getUserName()) &&
                            gitHubSCMSource.getRepository().equalsIgnoreCase(changedRepository.getRepositoryName())) {
                        for (Job<?, ?> job : owner.getAllJobs()) {
                            if (SCMHead.HeadByItem.findHead(job) instanceof PullRequestSCMHead prHead &&
                                    prHead.getNumber() == pullRequestId) {
                                jobConsumer.accept(job);
                            } else { // failed to match 'pullRequestJobNamePattern'
                                LOGGER.log(Level.FINE,
                                        "Skipping job [{0}] as it is not for pull request #{1}." +
                                                "If this is unexpected, make sure the job is configured with a " +
                                                "'discover pull requests...' behavior (see README)",
                                        new Object[] { job.getName(), pullRequestId });
                            }
                        }
                    }
                }
            }
        }
    }
}
