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
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonElement;
import com.google.gson.JsonParser;
import org.apache.maven.plugin.AbstractMojo;
import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.plugin.MojoFailureException;
import org.apache.maven.plugins.annotations.LifecyclePhase;
import org.apache.maven.plugins.annotations.Mojo;
import org.apache.maven.plugins.annotations.Parameter;
import org.apache.maven.plugins.annotations.ResolutionScope;
import org.apache.maven.project.MavenProject;
import org.jboss.fuse.tia.reports.GitStorage;

@Mojo(name = "show-reports", defaultPhase = LifecyclePhase.INITIALIZE,
        requiresDependencyResolution = ResolutionScope.RUNTIME, threadSafe = true)
public class ShowReports extends AbstractMojo {

    /**
     * Maven project.
     */
    @Parameter(property = "project", readonly = true)
    MavenProject project;

    @Parameter(property = "mvntia.file")
    String file;

    public final void execute() throws MojoExecutionException, MojoFailureException {
        String executionDir;
        File parent = new File(".").getAbsoluteFile();
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

        try {
            GitStorage storage = new GitStorage(executionDir);
            String str = storage.getNotes();
            if (str != null && !str.trim().startsWith("{")) {
                str = GitClient.uncompress(str);
            }
            JsonElement e = JsonParser.parseString(str);
            Gson gson = new GsonBuilder().setPrettyPrinting().create();
            String notes = gson.toJson(e);
            if (file != null) {
                Files.writeString(Paths.get(file), notes);
            } else {
                System.out.println(notes);
            }
        } catch (Exception e) {
            throw new MojoFailureException("Error setting up agent", e);
        }
    }

}
