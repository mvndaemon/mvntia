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

import java.lang.instrument.Instrumentation;
import java.util.List;
import java.util.Set;

public class Agent {

    private static Client client;

    private static String project;

    private static String reactorDeps;

    public static Client getClient() {
        return client;
    }

    public static String getProject() {
        return project;
    }

    public static String getReactorDeps() {
        return reactorDeps;
    }

    static {
        client = new Client() {
            @Override
            public Set<String> disabledTests(String project) {
                return Set.of();
            }
            @Override
            public void addReport(String project, String test, List<String> classes) {
            }
            @Override
            public void writeReport(String project) {
            }
            @Override
            public void log(String level, String message) {
            }
        };
        project = "";
    }

    public static void premain(String args, Instrumentation instrumentation) {
        try {
            AgentOptions options = new AgentOptions(args);
            client = new HttpClient(options.getPort());
            project = options.getProject();
            reactorDeps = options.getReactorDeps();
            Set<String> deps = reactorDeps.isBlank() ? Set.of() : Set.of(reactorDeps.split(";"));
            instrumentation.addTransformer(new AgentClassTransformer(deps));
        } catch (Throwable t) {
            t.printStackTrace();
            throw t;
        }
    }
}
