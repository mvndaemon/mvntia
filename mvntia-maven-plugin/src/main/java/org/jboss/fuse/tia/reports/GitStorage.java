/*
 * Copyright 2020 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.jboss.fuse.tia.reports;

import java.io.File;
import java.io.IOException;
import java.io.StringWriter;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.api.Status;
import org.eclipse.jgit.api.errors.GitAPIException;
import org.eclipse.jgit.api.errors.TransportException;
import org.eclipse.jgit.diff.DiffEntry;
import org.eclipse.jgit.lib.ObjectId;
import org.eclipse.jgit.lib.ObjectReader;
import org.eclipse.jgit.lib.Ref;
import org.eclipse.jgit.lib.RefUpdate;
import org.eclipse.jgit.notes.Note;
import org.eclipse.jgit.revwalk.RevCommit;
import org.eclipse.jgit.revwalk.RevWalk;
import org.eclipse.jgit.transport.RefSpec;
import org.eclipse.jgit.transport.RemoteConfig;
import org.eclipse.jgit.treewalk.CanonicalTreeParser;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class GitStorage {

    protected static final Logger LOGGER = LoggerFactory.getLogger(GitStorage.class);

    protected static String BASE_BRANCH = "master";

    protected static String GIT_NOTES_REF = "refs/notes/tests";

    protected final String executionDir;

    static {
//    SshSessionFactory.setInstance(new JschConfigSessionFactory() {
//      public void configure(OpenSshConfig.Host hc, Session session) {
//        session.setConfig("StrictHostKeyChecking", "no");
//      }
//    });
//        HttpTransport.setConnectionFactory(
//                new org.eclipse.jgit.transport.http.apache.HttpClientConnectionFactory());
    }

    public GitStorage() {
        try {
            File parent = new File(".").getCanonicalFile();
            boolean isGit = new File(parent, ".git").exists();
            while (parent.getParentFile() != null && !isGit) {
                parent = parent.getParentFile();
                isGit = new File(parent, ".git").exists();
            }
            if (isGit) {
                this.executionDir = parent.getCanonicalPath();
            } else {
                throw new RuntimeException("It is not a Git repository");
            }
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    public GitStorage(String executionDir) {
        this.executionDir = executionDir;
    }

    public ReportStatus getStatus() {
        try (Git git = open()) {
            if (getLocalBaseBranchSha(git) == null) {
                return ReportStatus.NO_COMMIT;
            }
            if (isClean(git)) {
                return ReportStatus.CLEAN;
            } else {
                return ReportStatus.DIRTY;
            }
        } catch (Exception e) {
            throw new RuntimeException("Error resolving the status in " + GIT_NOTES_REF, e);
        }
    }

    /**
     * Method called before any read or writing operation; whose purpose
     * is to initialize/prepare the required resources.
     */
    public ReportStatus prepare() {
        try (Git git = open()) {
            Ref ref = fetchNotesRef(git);
            if (ref == null) {
                createGitNotesRef(git);
                return ReportStatus.CLEAN;
            } else if (isClean(git)) {
                removeLastNote(git);
                return ReportStatus.CLEAN;
            } else {
                return ReportStatus.DIRTY;
            }
        } catch (Exception e) {
            throw new RuntimeException("Error removing the existing Git notes in " + GIT_NOTES_REF, e);
        }
    }

    public Ref fetchNotesRef(Git git) throws IOException, GitAPIException {
        Ref ref = git.getRepository().findRef(GIT_NOTES_REF);
        if (areNotesInRemote(git)) {
            git.fetch().setRemote("origin")
                    .setDryRun(false)
                    .setRefSpecs(new RefSpec(GIT_NOTES_REF + ":" + GIT_NOTES_REF))
                    .call();
        }
        return ref;
    }

    protected boolean areNotesInRemote(Git git) throws GitAPIException {
        try {
            return hasOrigin(git)
                    && git.lsRemote().call().stream()
                    .anyMatch(remoteRef -> remoteRef.getName().equals(GIT_NOTES_REF));
        } catch (TransportException e) {
            LOGGER.warn("Error accessing remote repository", e);
            return false;
        }
    }

    protected boolean hasOrigin(Git git) throws GitAPIException {
        return git.remoteList().call().stream().map(RemoteConfig::getName)
                .anyMatch("origin"::equals);
    }

    protected void removeLastNote(Git git) throws IOException, GitAPIException {
        RevWalk walk = new RevWalk(git.getRepository());
        RevCommit commit = walk.parseCommit(getBaseObjectId(git));
        git.notesRemove().setNotesRef(GIT_NOTES_REF)
                .setObjectId(commit).call();
    }

    public void createGitNotesRef(Git git) throws IOException {
        RefUpdate ru = git.getRepository().getRefDatabase().newUpdate(GIT_NOTES_REF, true);
        ru.setNewObjectId(getBaseObjectId(git));
        ru.update();
    }

    protected ObjectId getBaseObjectId(Git git) throws IOException {
        return getOriginHead(git)
                .orElse(Objects.requireNonNull(getHead(git), "Empty git repo ?"))
                .getObjectId();
    }

    protected Optional<Ref> getOriginHead(Git git) throws IOException {
        return Optional.ofNullable(git.getRepository().findRef("origin/" + BASE_BRANCH));
    }

    protected Ref getHead(Git git) throws IOException {
        return git.getRepository().findRef(git.getRepository().getBranch());
    }

    protected Ref getLocalBaseBranchSha(Git git) throws IOException {
        return git.getRepository().findRef(BASE_BRANCH);
    }

    protected Git open() throws IOException {
        return Git.open(new File(executionDir).getCanonicalFile());
    }

    public String getNotes() throws IOException {
        try (Git git = open()) {
            fetchNotesRef(git);

            RevWalk walk = new RevWalk(git.getRepository());
            RevCommit commit = walk.parseCommit(getBaseObjectId(git));
            Note note = git.notesShow().setNotesRef(GIT_NOTES_REF)
                    .setObjectId(commit).call();

            if (note != null) {
                LOGGER.debug(String.format("Git Notes found at %s for the commit %s", GIT_NOTES_REF,
                        commit.getName()));
                return new String(git.getRepository().open(note.getData()).getCachedBytes(),
                        StandardCharsets.UTF_8);
            } else {
                LOGGER.debug(String.format("Ops! Git Notes are not found at %s for the commit %s", GIT_NOTES_REF,
                        commit.getName()));
                return "";
            }

        } catch (GitAPIException e) {
            LOGGER.error("Error reading Git notes", e);
            throw new IOException("Error reading Git notes", e);
        }
    }

    /**
     * Returns a writer to update the base report. Used to incrementally add news reports
     *
     * @return a writer to update the base report.
     * @throws IOException
     */
    public Writer buildWriter() throws IOException {
        try (Git git = open()) {
            if (isClean(git)) {
                LOGGER.info("Tests Report:[READY]. Master branch is clean");
                return new GitNotesWriter();
            } else {
                LOGGER.info("Tests Report [OMITTED]. If you are in master branch, " +
                        "check that there are no pending changes to commit");
                return new StringWriter();
            }
        } catch (GitAPIException e) {
            LOGGER.error("Error checking master branch status", e);
            throw new IOException("Error building the git notes writer", e);
        }
    }


    protected RevCommit getBaseBranchCommit(Git git) throws IOException {
        String branch = git.getRepository().getBranch();
        RevWalk walk = new RevWalk(git.getRepository());
        Ref localBaseBranch = getLocalBaseBranchSha(git);
        return walk.parseCommit(localBaseBranch.getObjectId());
    }

    protected boolean isBaseBranch(Git git) throws GitAPIException, IOException {
        String branch = git.getRepository().getBranch();
        Ref localBaseBranch = getLocalBaseBranchSha(git);
        RevCommit baseBranchCommit = getBaseBranchCommit(git);
        boolean isDetachedCommit = (localBaseBranch.getName().equals("refs/heads/" + BASE_BRANCH)
                && baseBranchCommit.getName().equals(branch));
        return (branch.equals(BASE_BRANCH) || isDetachedCommit);
    }

    protected boolean isClean(Git git) throws GitAPIException, IOException {

        if (isBaseBranch(git)) {

            Optional<Ref> baseBranch = getOriginHead(git);
            if (baseBranch.isPresent()) {

                RevWalk walk = new RevWalk(git.getRepository());
                RevCommit baseBranchCommit = getBaseBranchCommit(git);

                RevCommit baseCommit = walk.parseCommit(getBaseObjectId(git));
                LOGGER.info(String.format("origin/master sha: [%s], head: [%s]",
                        baseCommit.getName(), baseBranchCommit.getName()));
                return baseCommit.equals(baseBranchCommit) && git.status().call().isClean();
            } else {
                // there is no origin
                Status status = git.status().call();
                boolean isClean = status.isClean();
                if (!isClean) {
                    LOGGER.info("Untracked Folders: " + Arrays.toString(status.getUntrackedFolders().toArray()));
                    LOGGER.info("Untracked Files: " + Arrays.toString(status.getUntracked().toArray()));
                    LOGGER.info("Changed Files: " + Arrays.toString(status.getChanged().toArray()));
                    LOGGER.info("Added Files: " + Arrays.toString(status.getAdded().toArray()));
                    LOGGER.info("Removed Files: " + Arrays.toString(status.getRemoved().toArray()));
                    LOGGER.info("Uncommitted Files: " + Arrays.toString(status.getUncommittedChanges().toArray()));
                }
                return isClean;
            }
        } else {
            String branch = git.getRepository().getBranch();
            LOGGER.info("The analyzed branch " + branch + " is not " + BASE_BRANCH);
        }
        return false;
    }


    public class GitNotesWriter extends StringWriter {

        public GitNotesWriter() {
        }

        @Override
        public void close() throws IOException {
            writeNote(getBuffer().toString());
        }
    }

    private void writeNote(String message) throws IOException {
        try (Git git = GitStorage.this.open()) {
            RevWalk walk = new RevWalk(git.getRepository());
            RevCommit commit = walk.parseCommit(GitStorage.this.getBaseObjectId(git));
            git.notesAdd().setNotesRef(GIT_NOTES_REF)
                    .setObjectId(commit)
                    .setMessage(message).call();
            LOGGER.info("Notes added to commit {}", commit);
        } catch (Exception e) {
            LOGGER.error("Error writing Tests Report in the Git Notes", e);
            throw new IOException("Error from the GitNotesWriter", e);
        }
    }

    private Set<String> getUpdatesFromTheBaseBranch(Git git, String baseRef, String headRef) throws IOException, GitAPIException {

        Set<String> files = new LinkedHashSet<>();
        Ref baseBranch = git.getRepository().findRef(baseRef);
        if (baseBranch == null) {
            return files;
        }
        Ref headBranch = git.getRepository().findRef(headRef);
        RevWalk walk = new RevWalk(git.getRepository());
        RevCommit baseCommit = walk.parseCommit(baseBranch.getObjectId());
        RevCommit headCommit = walk.parseCommit(headBranch.getObjectId());

        try (ObjectReader reader = git.getRepository().newObjectReader()) {
            CanonicalTreeParser oldTreeIter = new CanonicalTreeParser();
            oldTreeIter.reset(reader, baseCommit.getTree());
            CanonicalTreeParser newTreeIter = new CanonicalTreeParser();
            newTreeIter.reset(reader, headCommit.getTree());

            List<DiffEntry> diffs = git.diff()
                    .setNewTree(newTreeIter)
                    .setOldTree(oldTreeIter)
                    .call();
            for (DiffEntry entry : diffs) {
                files.add(entry.getNewPath());
            }
        }
        return files;
    }

    private Set<String> getModifiedOrChangedFiles(Git git) throws IOException, GitAPIException {
        Set<String> changed = new LinkedHashSet<>();
        Status status = git.status().call();
        changed.addAll(status.getModified());
        changed.addAll(status.getChanged());
        return changed;
    }

    /**
     * Returns the list of committed files whose commits are not yet in origin/master
     *
     * @return The list of committed files whose commits are not yet in origin/master
     * @throws IOException
     * @throws GitAPIException
     */
    public Set<String> getChangedAndCommittedFiles() throws IOException, GitAPIException {
        try (Git git = open()) {
            return getUpdatesFromTheBaseBranch(git, "origin/master",
                    git.getRepository().getBranch());
        }
    }

    /**
     * Returns the list of existing committed files with pending changes to commit
     *
     * @return the list of existing committed files with pending changes to commit
     * @throws IOException
     * @throws GitAPIException
     */
    public Set<String> getFilesWithUntrackedChanges() throws IOException, GitAPIException {
        try (Git git = open()) {
            return getModifiedOrChangedFiles(git);
        }
    }

}