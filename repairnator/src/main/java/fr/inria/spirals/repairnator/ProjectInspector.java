package fr.inria.spirals.repairnator;

import fr.inria.spirals.jtravis.entities.Build;
import org.apache.maven.cli.MavenCli;
import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.api.RemoteAddCommand;
import org.eclipse.jgit.lib.AnyObjectId;
import org.eclipse.jgit.lib.ObjectId;
import org.eclipse.jgit.lib.Ref;
import org.eclipse.jgit.revwalk.RevCommit;
import org.eclipse.jgit.revwalk.RevWalk;
import org.eclipse.jgit.transport.URIish;

import java.io.File;

/**
 * Created by urli on 26/12/2016.
 */
public class ProjectInspector {
    public static final String GITHUB_ROOT_REPO = "https://github.com/";

    private Build build;
    private String repoLocalPath;
    private boolean canBeBuilt;
    private boolean canBeCloned;

    public ProjectInspector(Build failingBuild) {
        this.build = failingBuild;
        this.canBeBuilt = false;
        this.canBeCloned = true;
    }

    public boolean canBeBuilt() {
        return this.canBeBuilt;
    }

    public Build getBuild() {
        return this.build;
    }

    public boolean canBeCloned() {
        return this.canBeCloned;
    }

    public void cloneInWorkspace(String workspace) {
        String repository = this.build.getRepository().getSlug();
        this.repoLocalPath = workspace+File.separator+repository;
        String repoRemotePath = GITHUB_ROOT_REPO+repository+".git";

        try {
            Launcher.LOGGER.debug("Cloning repository "+repository+" has in the following directory: "+repoLocalPath);
            Git git = Git.cloneRepository()
                    .setURI(repoRemotePath)
                    .setDirectory(new File(repoLocalPath))
                    .call();

            if (this.build.isPullRequest()) {
                Launcher.LOGGER.debug("Reproduce the PR for "+repository+" by fetching remote branch and merging.");
                String remoteBranchPath = GITHUB_ROOT_REPO+build.getPRRepository().getSlug()+".git";

                RemoteAddCommand remoteBranchCommand = git.remoteAdd();
                remoteBranchCommand.setName("PR");
                remoteBranchCommand.setUri(new URIish(remoteBranchPath));
                remoteBranchCommand.call();

                git.fetch().setRemote("PR").call();

                String commitHeadSha = this.build.getHeadCommit().getSha();
                String commitBaseSha = this.build.getBaseCommit().getSha();


                ObjectId commitHeadId = git.getRepository().resolve(commitHeadSha);
                ObjectId commitBaseId = git.getRepository().resolve(commitBaseSha);

                if (commitHeadId == null) {
                    Launcher.LOGGER.warn("Commit head ref cannot be retrieved in the repository: "+commitHeadSha+". Operation aborted.");
                    Launcher.LOGGER.debug(this.build.getHeadCommit());
                    this.canBeCloned = false;
                    return;
                }

                if (commitBaseId == null) {
                    Launcher.LOGGER.warn("Commit base ref cannot be retrieved in the repository: "+commitBaseSha+". Operation aborted.");
                    Launcher.LOGGER.debug(this.build.getBaseCommit());
                    this.canBeCloned = false;
                    return;
                }

                Launcher.LOGGER.debug("Get the commit "+commitHeadSha+" for repo "+repository);
                git.checkout().setName(commitHeadSha).call();

                RevWalk revwalk = new RevWalk(git.getRepository());
                RevCommit revCommitBase = revwalk.lookupCommit(commitBaseId);

                Launcher.LOGGER.debug("Do the merge with the PR commit for repo "+repository);
                git.merge().include(revCommitBase).call();
            } else {
                String commitCheckout = this.build.getCommit().getSha();

                Launcher.LOGGER.debug("Get the commit "+commitCheckout+" for repo "+repository);
                git.checkout().setName(commitCheckout).call();
            }



        } catch (Exception e) {
            Launcher.LOGGER.warn("Repository "+repository+" cannot be cloned.");
            Launcher.LOGGER.debug(e.toString());
            this.canBeCloned = false;
        }

        MavenCli cli = new MavenCli();

        System.setProperty("maven.test.skip","true");
        int result = cli.doMain(new String[]{"test"},
                repoLocalPath,
                System.out, System.err);

        if (result == 0) {
            this.canBeBuilt = true;
        } else {
            Launcher.LOGGER.info("Repository "+repository+" cannot be built. It will be ignored for the following steps.");
        }
        System.setProperty("maven.test.skip","false");
    }
}