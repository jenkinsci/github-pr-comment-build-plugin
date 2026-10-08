package com.adobe.jenkins.github_pr_comment_build;

import com.sun.net.httpserver.HttpServer;
import hudson.scm.NullSCM;
import jenkins.branch.Branch;
import jenkins.branch.BranchProperty;
import jenkins.branch.BranchSource;
import jenkins.branch.DefaultBranchPropertyStrategy;
import jenkins.scm.api.mixin.ChangeRequestCheckoutStrategy;
import jenkins.scm.api.SCMHeadOrigin;
import net.sf.json.JSONObject;
import org.jenkinsci.plugins.github_branch_source.BranchSCMHead;
import org.jenkinsci.plugins.github_branch_source.GitHubSCMSource;
import org.jenkinsci.plugins.github_branch_source.PullRequestSCMHead;
import org.jenkinsci.plugins.workflow.job.WorkflowJob;
import org.jenkinsci.plugins.workflow.multibranch.WorkflowMultiBranchProject;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.jvnet.hudson.test.JenkinsRule;
import org.jvnet.hudson.test.junit.jupiter.WithJenkins;

import java.net.InetSocketAddress;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.kohsuke.github.GHEvent.ISSUE_COMMENT;

/**
 * The PR job's {@link GitHubSCMSource} points at a local stub server that counts requests and answers 404, so no
 * request leaves the machine and any permission check fails.
 */
@WithJenkins
class IssueCommentGHEventSubscriberTest {

    private static JenkinsRule j;

    @BeforeAll
    static void setUp(JenkinsRule rule) {
        j = rule;
    }

    private final IssueCommentGHEventSubscriber subscriber = new IssueCommentGHEventSubscriber();
    private final AtomicInteger apiRequests = new AtomicInteger();
    private HttpServer api;

    @BeforeEach
    void startApiStub() throws Exception {
        api = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
        api.createContext("/", exchange -> {
            apiRequests.incrementAndGet();
            exchange.sendResponseHeaders(404, -1);
            exchange.close();
        });
        api.start();
    }

    @AfterEach
    void stopApiStub() {
        api.stop(0);
    }

    private void createPullRequestJob(String projectName, String repository, int number) throws Exception {
        TriggerPRCommentBranchProperty commentProp = new TriggerPRCommentBranchProperty("^build this$", false);
        GitHubSCMSource source = new GitHubSCMSource("someOwner", repository);
        source.setApiUri("http://localhost:" + api.getAddress().getPort());
        WorkflowMultiBranchProject project = j.jenkins.createProject(WorkflowMultiBranchProject.class, projectName);
        project.getSourcesList().add(new BranchSource(
                source, new DefaultBranchPropertyStrategy(new BranchProperty[] {commentProp})));

        PullRequestSCMHead head = new PullRequestSCMHead("PR-" + number, "someOwner", repository, "feature", number,
                new BranchSCMHead("main"), SCMHeadOrigin.DEFAULT, ChangeRequestCheckoutStrategy.HEAD);
        WorkflowJob job = project.getProjectFactory()
                .newInstance(new Branch(source.getId(), head, new NullSCM(), List.of(commentProp)));
        project.addLoadedChild(job, job.getName());
    }

    private static String commentPayload(String repository, int number, String body) {
        String repoUrl = "https://github.com/someOwner/" + repository;
        JSONObject payload = new JSONObject();
        payload.put("action", "created");
        payload.put("repository", new JSONObject().element("html_url", repoUrl));
        payload.put("issue", new JSONObject()
                .element("html_url", repoUrl + "/pull/" + number)
                .element("number", number)
                .element("pull_request", new JSONObject().element("url", repoUrl + "/pulls/" + number)));
        payload.put("comment", new JSONObject()
                .element("body", body)
                .element("html_url", repoUrl + "/pull/" + number + "#issuecomment-1")
                .element("user", new JSONObject().element("login", "someone")));
        return payload.toString();
    }

    @Test
    void onEvent_makesNoApiRequests_whenCommentDoesNotMatch() throws Exception {
        createPullRequestJob("non-matching", "nonMatchingRepo", 1);

        subscriber.onEvent(ISSUE_COMMENT, commentPayload("nonMatchingRepo", 1, "looks good to me"));

        assertEquals(0, apiRequests.get(), "a comment that triggers nothing should not check the author's permissions");
    }

    @Test
    void onEvent_checksPermissions_whenCommentMatches() throws Exception {
        createPullRequestJob("matching", "matchingRepo", 1);

        subscriber.onEvent(ISSUE_COMMENT, commentPayload("matchingRepo", 1, "build this"));

        assertTrue(apiRequests.get() > 0, "a matching comment should check the author's permissions");
    }
}
