# GitHub Pull Request Comment Build Plugin

This plugin listens for comments on pull requests and will trigger a GitHub multibranch
job if a comment body matches the configured value, such as "REBUILD". This is implemented
as a branch property on multibranch jobs.

To enable this behavior, simply add the branch property to the multibranch job and 
configure the string to use. Make sure that you have a GitHub server properly connected 
via the Jenkins configuration, or else this will not work.

# Plugin maintenance

## Releasing new versions

Use the instructions found in [this wiki page](https://wiki.jenkins.io/display/JENKINS/Hosting+Plugins).
