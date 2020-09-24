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
import java.util.Map;
import java.util.Properties;
import java.util.Set;
import java.util.stream.Collectors;

import org.apache.maven.artifact.Artifact;
import org.apache.maven.plugin.AbstractMojo;
import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.plugin.MojoFailureException;
import org.apache.maven.plugins.annotations.LifecyclePhase;
import org.apache.maven.plugins.annotations.Mojo;
import org.apache.maven.plugins.annotations.Parameter;
import org.apache.maven.plugins.annotations.ResolutionScope;
import org.apache.maven.project.MavenProject;
import org.jboss.fuse.tia.agent.AgentOptions;

@Mojo(name = "prepare-agent", defaultPhase = LifecyclePhase.INITIALIZE,
        requiresDependencyResolution = ResolutionScope.RUNTIME, threadSafe = true)
public class PrepareAgent extends AbstractMojo {

    /**
     * Name of the MvnTia Agent artifact.
     */
    static final String AGENT_ARTIFACT_NAME = "org.jboss.fuse.mvntia:mvntia-agent";
    /**
     * Name of the property used in maven-surefire-plugin.
     */
    static final String SUREFIRE_ARG_LINE = "argLine";

    /**
     * Maven project.
     */
    @Parameter(property = "project", readonly = true)
    MavenProject project;

    /**
     * Flag used to suppress execution.
     */
    @Parameter(property = "mvntia.skip", defaultValue = "false")
    boolean skip;

    /**
     * Property name to set
     */
    @Parameter(defaultValue = SUREFIRE_ARG_LINE)
    String propertyName;

    /**
     * Map of plugin artifacts.
     */
    @Parameter(property = "plugin.artifactMap", required = true, readonly = true)
    Map<String, Artifact> pluginArtifactMap;

    public final void execute() throws MojoExecutionException, MojoFailureException {
        if (skip) {
            getLog().info("Skipping mvntia execution because property mvntia.skip is set.");
        } else {
            executeMojo();
        }
    }

    protected void executeMojo() throws MojoExecutionException, MojoFailureException {
        try {
            if (project.getPackaging().equals("pom")) {
                getLog().debug("Ignoring pom packaging");
                return;
            }

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

            Set<String> reactorDeps = project.getArtifacts().stream()
                    .map(a -> a.getFile().toString())
                    .filter(f -> f.startsWith(executionDir))
                    .collect(Collectors.toSet());

            Server server = new Server(new GitClient(executionDir, reactorDeps));

            final String name = propertyName;
            final Properties projectProperties = project.getProperties();
            final String oldValue = projectProperties.getProperty(name);
            final String newValue = new AgentOptions()
                    .port(server.getPort())
                    .prependVMArguments(oldValue, getAgentJarFile());
            getLog().info(name + " set to " + newValue);
            projectProperties.setProperty(name, newValue);
        } catch (Exception e) {
            throw new MojoFailureException("Error setting up agent", e);
        }
    }

    File getAgentJarFile() {
        final Artifact mvntiaAgentArtifact = pluginArtifactMap.get(AGENT_ARTIFACT_NAME);
        return mvntiaAgentArtifact.getFile();
    }

}
