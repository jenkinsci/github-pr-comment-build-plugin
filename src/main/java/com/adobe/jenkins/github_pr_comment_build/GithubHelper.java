package com.adobe.jenkins.github_pr_comment_build;

import com.cloudbees.plugins.credentials.common.StandardCredentials;
import hudson.model.Job;
import java.io.IOException;
import javax.annotation.Nonnull;
import jenkins.scm.api.SCMSource;
import org.jenkinsci.plugins.github_branch_source.Connector;
import org.jenkinsci.plugins.github_branch_source.GitHubSCMSource;
import org.kohsuke.github.GHPermissionType;
import org.kohsuke.github.GHRepository;
import org.kohsuke.github.GitHub;
import org.slf4j.LoggerFactory;
import org.slf4j.Logger;

/**
 * client utility methods
 */
public class GithubHelper {

    private final static Logger LOG = LoggerFactory.getLogger(GithubHelper.class);

    private GithubHelper() {
        // private
    }

    public static boolean isAuthorized(final Job<?, ?> job, final String author, String minimumPermissions) {
        try {
            GHRepository ghRepository = getGHRepository(job);
            if (ghRepository == null) {
                LOG.debug("Could not retrieve GitHub repository, User {} not authorized", author);
                return false;
            }
            GHPermissionType authorPermissions = ghRepository.getPermission(author);
            boolean authorized = false;
            switch (GHPermissionType.valueOf(minimumPermissions)) {
                case NONE:
                    authorized = true;
                    break;
                default: // break intentionally omitted
                case WRITE:
                    if(authorPermissions == GHPermissionType.WRITE || authorPermissions == GHPermissionType.ADMIN) {
                        authorized = true;
                    }
                    break;
                case ADMIN:
                    if(authorPermissions == GHPermissionType.ADMIN) {
                        authorized = true;
                    }
                    break;
            }

            LOG.debug("User {} authorized: {}", author, authorized);
            return authorized;
        } catch (final IOException | IllegalArgumentException e) {
            LOG.debug(String.format(
                    "Received an exception while trying to check if user %s is a collaborator for repo of job %s",
                    author, job.getFullName()), e);
            return false;
        }
    }

    public static GitHub getGitHub(SCMSource scmSource, @Nonnull final Job<?, ?> job) {
        if (scmSource instanceof GitHubSCMSource gitHubSource) {
            final StandardCredentials credentials = Connector.lookupScanCredentials(
                    job,
                    gitHubSource.getApiUri(),
                    gitHubSource.getCredentialsId(),
                    gitHubSource.getRepoOwner()
            );
            try {
                return Connector.connect(gitHubSource.getApiUri(), credentials);
            } catch (final IOException | IllegalArgumentException e) {
                LOG.debug(String.format("Received an exception while trying to retrieve a GitHub connection for job %s",
                        job.getFullName()), e);
                return null;
            }
        }

        throw new IllegalArgumentException("Job's SCM is not GitHub.");
    }

    private static GHRepository getGHRepository(@Nonnull final Job<?, ?> job) throws IOException {
        final SCMSource scmSource = SCMSource.SourceByItem.findSource(job);
        GitHub github = getGitHub(scmSource, job);
        if (github == null) {
            LOG.debug("Could not get GitHub repository, GitHub connection failed");
            return null;
        }
        // Already checked by getGitHub method
        final GitHubSCMSource gitHubSource = (GitHubSCMSource) scmSource;
        return github.getRepository(gitHubSource.getRepoOwner() + "/" + gitHubSource.getRepository());
    }
}
