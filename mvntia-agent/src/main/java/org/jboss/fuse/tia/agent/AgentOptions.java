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
/*
 * Copyright (c) 2009, 2020 Mountainminds GmbH & Co. KG and Contributors
 *
 * The JaCoCo Java Code Coverage Library and all included documentation is
 * made available by Mountainminds GmbH & Co. KG, Munich. Except indicated
 * below, the Content is provided to you under the terms and conditions of
 * the Eclipse Public License Version 2.0 ("EPL"). A copy of the EPL is
 * available at https://www.eclipse.org/legal/epl-2.0/.
 *
 * Please visit http://www.jacoco.org/jacoco/trunk/doc/license.html for the
 * complete license information including third party licenses and trademarks.
 */
package org.jboss.fuse.tia.agent;

import java.io.File;
import java.util.Arrays;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

import static java.lang.String.format;

public class AgentOptions {

    public static final String DIGEST = "digest";

    public static final String FORCE = "force";

    public static final String PORT = "port";

    public static final String PROJECT = "project";

    public static final String REACTOR_DEPS = "reactorDeps";

    private static final Collection<String> VALID_OPTIONS = Arrays.asList(DIGEST, FORCE, PORT, PROJECT, REACTOR_DEPS);

    private static final Pattern OPTION_SPLIT = Pattern.compile(",(?=[a-zA-Z0-9_\\-]+=)");

    private final Map<String, String> options;

    /**
     * New instance with all values set to default.
     */
    public AgentOptions() {
        this.options = new HashMap<>();
    }

    /**
     * New instance parsed from the given option string.
     *
     * @param optionstr string to parse or <code>null</code>
     */
    public AgentOptions(final String optionstr) {
        this();
        if (optionstr != null && optionstr.length() > 0) {
            for (final String entry : OPTION_SPLIT.split(optionstr)) {
                final int pos = entry.indexOf('=');
                if (pos == -1) {
                    throw new IllegalArgumentException(format(
                            "Invalid agent option syntax \"%s\".", optionstr));
                }
                final String key = entry.substring(0, pos);
                if (!VALID_OPTIONS.contains(key)) {
                    throw new IllegalArgumentException(
                            format("Unknown agent option \"%s\".", key));
                }

                final String value = entry.substring(pos + 1);
                setOption(key, value);
            }
        }
    }

    public String getDigest() {
        return getOption(DIGEST, "");
    }

    public boolean isForce() {
        return getOption(FORCE, false);
    }

    public int getPort() {
        return getOption(PORT, 0);
    }

    public String getProject() {
        return getOption(PROJECT, "");
    }

    public String getReactorDeps() {
        return getOption(REACTOR_DEPS, "");
    }

    public AgentOptions digest(final String project) {
        setOption(DIGEST, project);
        return this;
    }

    public AgentOptions force(boolean force) {
        setOption(FORCE, force);
        return this;
    }

    /**
     * Sets the port on which to listen to when output is <code>tcpserver</code>
     * or the port to connect to when output is <code>tcpclient</code>
     *
     * @param port port to listen on or connect to
     */
    public AgentOptions port(final int port) {
        setOption(PORT, port);
        return this;
    }

    public AgentOptions project(final String project) {
        setOption(PROJECT, project);
        return this;
    }

    public AgentOptions reactorDeps(final String reactorDeps) {
        setOption(REACTOR_DEPS, reactorDeps);
        return this;
    }

    /**
     * Generate required quotes JVM argument based on current configuration and
     * prepends it to the given argument command line. If a agent with the same
     * JAR file is already specified this parameter is removed from the existing
     * command line.
     *
     * @param arguments existing command line arguments or <code>null</code>
     * @param agentJarFile location of the Agent Jar
     * @return VM command line arguments prepended with configured agent
     */
    public String prependVMArguments(final String arguments,
                                     final File agentJarFile,
                                     final boolean debug) {
        final List<String> args = CommandLineSupport.split(arguments);
        final String plainAgent = String.format("-javaagent:%s", agentJarFile);
        args.removeIf(s -> s.startsWith(plainAgent));
        args.add(0, getVMArgument(agentJarFile));
        args.add(1, "-Djunit.jupiter.extensions.autodetection.enabled=true");
        if (debug) {
            args.add(2, "-agentlib:jdwp=transport=dt_socket,server=y,suspend=y,address=*:5005");
        }
        return CommandLineSupport.quote(args);
    }

    /**
     * Generate required JVM argument based on current configuration and
     * supplied agent jar location.
     *
     * @param agentJarFile location of the agent Jar
     * @return Argument to pass to create new VM with coverage enabled
     */
    public String getVMArgument(final File agentJarFile) {
        return String.format("-javaagent:%s=%s", agentJarFile, this);
    }

    /**
     * Creates a string representation that can be passed to the agent via the
     * command line. Might be the empty string, if no options are set.
     */
    @Override
    public String toString() {
        return VALID_OPTIONS.stream()
                .filter(options::containsKey)
                .map(k -> k + "=" + options.get(k))
                .collect(Collectors.joining(","));
    }

    private void setOption(final String key, final boolean value) {
        setOption(key, Boolean.toString(value));
    }

    private void setOption(final String key, final int value) {
        setOption(key, Integer.toString(value));
    }

    private void setOption(final String key, final String value) {
        options.put(key, value);
    }

    private boolean getOption(final String key, final boolean defaultValue) {
        final String value = options.get(key);
        return value == null ? defaultValue : Boolean.parseBoolean(value);
    }

    private int getOption(final String key, final int defaultValue) {
        final String value = options.get(key);
        return value == null ? defaultValue : Integer.parseInt(value);
    }

    private String getOption(final String key, final String defaultValue) {
        final String value = options.get(key);
        return value == null ? defaultValue : value;
    }

}
