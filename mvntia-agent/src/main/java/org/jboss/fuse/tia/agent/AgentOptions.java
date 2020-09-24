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

    public static final String PORT = "port";

    private static final Collection<String> VALID_OPTIONS = Arrays.asList(PORT);

    private static final Pattern OPTION_SPLIT = Pattern
            .compile(",(?=[a-zA-Z0-9_\\-]+=)");

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
     * @param optionstr
     *            string to parse or <code>null</code>
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

    public int getPort() {
        return getOption(PORT, 0);
    }

    /**
     * Sets the port on which to listen to when output is <code>tcpserver</code>
     * or the port to connect to when output is <code>tcpclient</code>
     *
     * @param port
     *            port to listen on or connect to
     */
    public AgentOptions port(final int port) {
        setOption(PORT, port);
        return this;
    }

    /**
     * Generate required quotes JVM argument based on current configuration and
     * prepends it to the given argument command line. If a agent with the same
     * JAR file is already specified this parameter is removed from the existing
     * command line.
     *
     * @param arguments
     *            existing command line arguments or <code>null</code>
     * @param agentJarFile
     *            location of the JaCoCo Agent Jar
     * @return VM command line arguments prepended with configured JaCoCo agent
     */
    public String prependVMArguments(final String arguments,
                                     final File agentJarFile) {
        final List<String> args = CommandLineSupport.split(arguments);
        final String plainAgent = String.format("-javaagent:%s", agentJarFile);
        args.removeIf(s -> s.startsWith(plainAgent));
        args.add(0, getVMArgument(agentJarFile));
        args.add(1, "-Djunit.jupiter.extensions.autodetection.enabled=true");
        return CommandLineSupport.quote(args);
    }

    /**
     * Generate required JVM argument based on current configuration and
     * supplied agent jar location.
     *
     * @param agentJarFile
     *            location of the JaCoCo Agent Jar
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

    private void setOption(final String key, final int value) {
        setOption(key, Integer.toString(value));
    }

    private void setOption(final String key, final String value) {
        options.put(key, value);
    }

    private int getOption(final String key, final int defaultValue) {
        final String value = options.get(key);
        return value == null ? defaultValue : Integer.parseInt(value);
    }

}
