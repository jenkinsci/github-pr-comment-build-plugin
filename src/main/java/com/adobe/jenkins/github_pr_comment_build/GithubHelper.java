package com.adobe.jenkins.github_pr_comment_build;

import com.cloudbees.plugins.credentials.common.StandardCredentials;
import com.cloudbees.plugins.credentials.common.StandardUsernamePasswordCredentials;
import hudson.model.Job;
import java.io.IOException;
import java.net.URI;
import java.util.Collections;
import java.util.List;
import java.util.stream.Collectors;
import javax.annotation.Nonnull;
import jenkins.scm.api.SCMSource;
import org.eclipse.egit.github.core.RepositoryId;
import org.eclipse.egit.github.core.User;
import org.eclipse.egit.github.core.client.GitHubClient;
import org.eclipse.egit.github.core.service.CollaboratorService;
import org.jenkinsci.plugins.github_branch_source.Connector;
import org.jenkinsci.plugins.github_branch_source.GitHubSCMSource;
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

    public static boolean isAuthorized(final Job<?, ?> job, final String author) {
        GitHubClient client = getGitHubClient(job);
        RepositoryId repository = getRepositoryId(job);
        CollaboratorService collaboratorService = new CollaboratorService(client);

        try {
            boolean authorized = collaboratorService.isCollaborator(repository, author);
            LOG.debug("User {} autorized: {}", author, authorized);
            return authorized;

        } catch (final IOException e) {
            LOG.debug("Received an exception while trying to check if user {} is a collaborator of repository: {}",
                    author, repository, e);
            return false;
        }
    }

    public static List<String> getCollaborators(@Nonnull final Job<?, ?> job) {
        GitHubClient client = getGitHubClient(job);
        RepositoryId repository = getRepositoryId(job);
        CollaboratorService collaboratorService = new CollaboratorService(client);

        try {
            return collaboratorService.getCollaborators(repository)
                    .stream()
                    .map(User::getLogin)
                    .collect(Collectors.toList());
        } catch (final IOException e) {
            LOG.debug("Received an exception while trying to retrieve the collaborators for the repository: {}",
                    repository, e);
            return Collections.emptyList();
        }
    }

    public static GitHubClient getGitHubClient(@Nonnull final Job<?, ?> job) {
        SCMSource scmSource = SCMSource.SourceByItem.findSource(job);
        if (scmSource instanceof GitHubSCMSource) {
            GitHubSCMSource gitHubSource = (GitHubSCMSource) scmSource;

            URI uri = URI.create(gitHubSource.getApiUri());
            GitHubClient client = new GitHubClient(uri.getHost(), uri.getPort(), uri.getScheme());

            // configure credentials
            if (gitHubSource.getCredentialsId() != null) {
                StandardCredentials credentials = Connector.lookupScanCredentials(
                        job, gitHubSource.getApiUri(), gitHubSource.getCredentialsId());

                if (credentials instanceof StandardUsernamePasswordCredentials) {
                    StandardUsernamePasswordCredentials c = (StandardUsernamePasswordCredentials) credentials;
                    String userName = c.getUsername();
                    String password = c.getPassword().getPlainText();
                    client.setCredentials(userName, password);
                }
            }
            return client;
        }
        throw new IllegalArgumentException("Job's SCM is not GitHub.");
    }

    public static RepositoryId getRepositoryId(@Nonnull final Job<?, ?> job) {
        SCMSource src = SCMSource.SourceByItem.findSource(job);
        if (src instanceof GitHubSCMSource) {
            GitHubSCMSource source = (GitHubSCMSource) src;
            if (source.getCredentialsId() != null) {
                return RepositoryId.create(source.getRepoOwner(), source.getRepository());
            }
        }
        return null;
    }
}
