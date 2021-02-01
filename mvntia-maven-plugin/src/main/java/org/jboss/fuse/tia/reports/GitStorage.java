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
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.TreeSet;

import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.api.errors.GitAPIException;
import org.eclipse.jgit.diff.DiffEntry;
import org.eclipse.jgit.errors.RepositoryNotFoundException;
import org.eclipse.jgit.lib.ObjectReader;
import org.eclipse.jgit.lib.Ref;
import org.eclipse.jgit.notes.Note;
import org.eclipse.jgit.revwalk.RevCommit;
import org.eclipse.jgit.revwalk.RevWalk;
import org.eclipse.jgit.treewalk.CanonicalTreeParser;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class GitStorage implements Storage {

    protected static final Logger LOGGER = LoggerFactory.getLogger(GitStorage.class);

    protected static String GIT_NOTES_REF = "refs/notes/tests";

    protected final String executionDir;

    public GitStorage(String executionDir) {
        this.executionDir = executionDir;
    }

    @Override
    public State getState() throws IOException {
        try (Git git = open()) {
            Ref head = getHead(git);
            if (head == null) {
                LOGGER.info("[mvntia] Can not retrieve HEAD");
                return null;
            }

            String noteData = null;
            Set<String> modified = null;
            Set<String> uncommitted;

            org.eclipse.jgit.api.Status status = git.status().call();
            uncommitted = new TreeSet<>();
            uncommitted.addAll(status.getUncommittedChanges());
            uncommitted.addAll(status.getUntracked());

            Note note = null;
            RevWalk walk = new RevWalk(git.getRepository());
            RevCommit headCommit = Objects.requireNonNull(walk.parseCommit(head.getObjectId()));
            RevCommit baseCommit = headCommit;
            while (note == null && baseCommit != null) {
                LOGGER.info("[mvntia] checking note for " + baseCommit);
                note = git.notesShow().setNotesRef(GIT_NOTES_REF).setObjectId(baseCommit).call();
                if (note == null) {
                    RevCommit[] parents = baseCommit.getParents();
                    baseCommit = parents != null && parents.length > 0 ? walk.parseCommit(parents[0]) : null;
                }
            }
            if (note != null) {
                // TODO: use streaming api directly ?
                noteData = new String(git.getRepository().open(note.getData()).getCachedBytes(),
                        StandardCharsets.UTF_8);
            } else {
                LOGGER.info("[mvntia] not note found");
            }
            if (baseCommit != null) {
                modified = new TreeSet<>();
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
                        modified.add(entry.getNewPath());
                    }
                }
            }

            return new State(noteData, modified, uncommitted);
        } catch (RepositoryNotFoundException e) {
            return null;
        } catch (GitAPIException e) {
            throw new IllegalStateException(e);
        }
    }

    public void writeNotes(String message) throws IOException {
        try (Git git = open()) {
            if (git.status().call().isClean()) {
                RevCommit commit = getHeadCommit(git);
                git.notesAdd().setNotesRef(GIT_NOTES_REF)
                        .setObjectId(commit)
                        .setMessage(message).call();
                LOGGER.info("Notes added to commit {}", commit);
            } else {
                LOGGER.info("The repository is not clean, the notes won't be written");
            }
        } catch (Exception e) {
            LOGGER.error("Error writing git notes", e);
            throw new IOException("Error writing git notes", e);
        }
    }

    public void removeNotes() throws IOException {
        try (Git git = open()) {
            RevCommit commit = getHeadCommit(git);
            git.notesRemove().setNotesRef(GIT_NOTES_REF)
                    .setObjectId(commit).call();
            LOGGER.info("Notes removed from commit {}", commit);
        } catch (Exception e) {
            LOGGER.error("Error removing git notes", e);
            throw new IOException("Error removing git notes", e);
        }
    }

    protected RevCommit getHeadCommit(Git git) throws IOException {
        return git.getRepository().parseCommit(getHead(git).getObjectId());
    }

    protected Ref getHead(Git git) throws IOException {
        String branch = git.getRepository().getBranch();
        return branch != null ? git.getRepository().findRef(branch) : null;
    }

    protected Git open() throws IOException {
        return Git.open(new File(executionDir).getCanonicalFile());
    }


}
