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
package org.jboss.fuse.tia.maven;

import java.io.File;
import java.io.IOException;

import org.apache.maven.plugin.AbstractMojo;
import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.plugin.MojoFailureException;
import org.apache.maven.plugins.annotations.Parameter;
import org.apache.maven.project.MavenProject;
import org.jboss.fuse.tia.agent.Client;
import org.jboss.fuse.tia.reports.GitClient;
import org.jboss.fuse.tia.reports.GitStorage;
import org.jboss.fuse.tia.reports.Storage;

public abstract class AbstractTiaMojo extends AbstractMojo {

    /**
     * Maven project.
     */
    @Parameter(property = "project", readonly = true)
    MavenProject project;

    @Parameter(property = "mvntia.git.notes.ref")
    String gitNotesRef = GitStorage.GIT_NOTES_REF;

    String executionDir;

    public final void execute() throws MojoExecutionException, MojoFailureException {
        try {
            doExecute();
        } catch (Exception e) {
            throw new MojoFailureException("Error executing mojo", e);
        }
    }

    protected abstract void doExecute() throws Exception;

    public String getExecutionDir() {
        if (executionDir == null) {
            String dir = System.getProperty("maven.multiModuleProjectDirectory", ".");
            File parent = new File(dir).getAbsoluteFile();
            boolean isGit = new File(parent, ".git").exists();
            while (parent.getParentFile() != null && !isGit) {
                parent = parent.getParentFile();
                isGit = new File(parent, ".git").exists();
            }
            if (isGit) {
                executionDir = parent.getAbsolutePath();
            } else {
                throw new RuntimeException("It is not a Git repository");
            }
        }
        return executionDir;
    }

    protected Storage createStorage() throws IOException {
        return new GitStorage(getExecutionDir(), gitNotesRef);
    }

    protected Client createClient() throws IOException {
        GitClient client = new GitClient(createStorage(), getLog());
        return client;
    }

}
