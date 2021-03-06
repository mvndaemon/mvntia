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
package org.jboss.fuse.tia.junit5;

import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.BlockingDeque;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.LinkedBlockingDeque;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.stream.Collectors;

import org.jboss.fuse.tia.agent.Agent;
import org.jboss.fuse.tia.agent.AgentClassTransformer;
import org.junit.platform.engine.TestExecutionResult;
import org.junit.platform.engine.TestSource;
import org.junit.platform.engine.support.descriptor.ClassSource;
import org.junit.platform.engine.support.descriptor.MethodSource;
import org.junit.platform.launcher.TestExecutionListener;
import org.junit.platform.launcher.TestIdentifier;
import org.junit.platform.launcher.TestPlan;

public class TiaTestListener implements TestExecutionListener {

    private static final Logger LOGGER = Logger.getLogger(TiaTestListener.class.getName());

    private static final String STOP = "#-#-STOP-#-#";

    private final BlockingDeque<Report> reports = new LinkedBlockingDeque<>();
    private final Thread runner;
    private final Map<String, TestExecutionResult.Status> results = new ConcurrentHashMap<>();

    public TiaTestListener() {
        runner = new Thread(this::sendReports);
        runner.start();
    }

    @Override
    public void executionStarted(TestIdentifier testIdentifier) {
        TestSource source = testIdentifier.getSource().orElse(null);
        if (source instanceof ClassSource) {
            Agent.log("debug", "executionStarted: " + testIdentifier);
        }
    }

    @Override
    public void executionFinished(TestIdentifier testIdentifier, TestExecutionResult testExecutionResult) {
        TestSource source = testIdentifier.getSource().orElse(null);
        if (source instanceof MethodSource) {
            String test = ((MethodSource) source).getClassName();
            results.merge(test, testExecutionResult.getStatus(), (s1, s2) -> s1 != TestExecutionResult.Status.SUCCESSFUL ? s1 : s2);
        }
        if (source instanceof ClassSource) {
            Set<String> classes = AgentClassTransformer.getReferencedClasses();
            String test = ((ClassSource) source).getClassName();
            results.merge(test, testExecutionResult.getStatus(), (s1, s2) -> s1 != TestExecutionResult.Status.SUCCESSFUL ? s1 : s2);
            if (results.remove(test) == TestExecutionResult.Status.SUCCESSFUL) {
                Collection<String> names = classes.stream()
                        .map(s -> {
                            int i = s.indexOf('$');
                            return i > 0 ? s.substring(0, i) : s;
                        })
                        .filter(s -> !test.equals(s))
                        .collect(Collectors.toCollection(TreeSet::new));
                addReport(test, names);
                AgentClassTransformer.cleanUp();
                Agent.log("debug", "executionFinished: " + test + ": referenced classes: " + names);
            }
        }
    }

    @Override
    public void testPlanExecutionFinished(TestPlan testPlan) {
        addReport(STOP, List.of());
        try {
            runner.join();
        } catch (InterruptedException e) {
            LOGGER.log(Level.SEVERE, "Error waiting for runner thread", e);
        }
        Agent.writeReport();
    }

    private void addReport(String test, Collection<String> classes) {
        reports.add(new Report(test, classes));
    }

    private void sendReports() {
        try {
            while (true) {
                Report report = reports.take();
                if (Objects.equals(STOP, report.test)) {
                    break;
                }
                Agent.addReport(report.test, report.classes);
            }
        } catch (Exception e) {
            LOGGER.log(Level.SEVERE, "Error sending reports", e);
        }
    }

    static class Report {
        final String test;
        final Collection<String> classes;

        public Report(String test, Collection<String> classes) {
            this.test = test;
            this.classes = classes;
        }
    }
}
