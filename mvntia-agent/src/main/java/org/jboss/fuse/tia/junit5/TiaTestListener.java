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

import java.util.Objects;
import java.util.Set;
import java.util.concurrent.BlockingDeque;
import java.util.concurrent.LinkedBlockingDeque;
import java.util.logging.Level;
import java.util.logging.LogManager;
import java.util.logging.Logger;

import org.jboss.fuse.tia.agent.Agent;
import org.jboss.fuse.tia.agent.AgentClassTransformer;
import org.junit.platform.engine.TestExecutionResult;
import org.junit.platform.engine.support.descriptor.ClassSource;
import org.junit.platform.engine.support.descriptor.MethodSource;
import org.junit.platform.launcher.TestExecutionListener;
import org.junit.platform.launcher.TestIdentifier;
import org.junit.platform.launcher.TestPlan;

public class TiaTestListener implements TestExecutionListener {

    private static final Logger LOGGER = LogManager.getLogManager().getLogger(TiaTestListener.class.getName());

    private static final String STOP = "#-#-STOP-#-#";

    private TestPlan plan;
    private final BlockingDeque<Report> reports = new LinkedBlockingDeque<>();
    private final Thread runner;

    public TiaTestListener() {
        runner = new Thread(this::sendReports);
        runner.start();
    }

    @Override
    public void testPlanExecutionStarted(TestPlan testPlan) {
        this.plan = testPlan;
    }

    private String getClassName(TestIdentifier testIdentifier) {
        return plan.getParent(testIdentifier)
                .flatMap(TestIdentifier::getSource)
                .filter(ClassSource.class::isInstance)
                .map(ClassSource.class::cast)
                .map(ClassSource::getClassName)
                .orElseThrow(() -> new IllegalStateException("Invalid test class name"));
    }

    private String getMethodName(TestIdentifier testIdentifier) {
        return testIdentifier.getSource()
                .filter(MethodSource.class::isInstance)
                .map(MethodSource.class::cast)
                .map(MethodSource::getMethodName)
                .orElseThrow(() -> new IllegalStateException("Invalid test method name"));
    }

    @Override
    public void executionStarted(TestIdentifier testIdentifier) {
        if (testIdentifier.isTest()) {
            AgentClassTransformer.cleanUp();
        }
    }

    @Override
    public void executionFinished(TestIdentifier testIdentifier, TestExecutionResult testExecutionResult) {
        if (testIdentifier.isTest()) {
            Set<String> classes = AgentClassTransformer.getReferencedClasses();
            if (!classes.isEmpty()) {
                addReport(getClassName(testIdentifier), getMethodName(testIdentifier), classes);
            }
        }
    }

    @Override
    public void testPlanExecutionFinished(TestPlan testPlan) {
        addReport(STOP, STOP, Set.of());
        try {
            runner.join();
        } catch (InterruptedException e) {
            LOGGER.log(Level.SEVERE, "Error waiting for runner thread", e);
        }
        Agent.getClient().writeReports();
    }

    private void addReport(String test, String method, Set<String> classes) {
        reports.add(new Report(test, method, classes));
    }

    private void sendReports() {
        try {
            while (true) {
                Report report = reports.take();
                if (Objects.equals(STOP, report.test)) {
                    break;
                }
                Agent.getClient().addReport(report.test, report.method, report.classes);
            }
        } catch (Exception e) {
            LOGGER.log(Level.SEVERE, "Error sending reports", e);
        }
    }


    static class Report {
        final String test;
        final String method;
        final Set<String> classes;

        public Report(String test, String method, Set<String> classes) {
            this.test = test;
            this.method = method;
            this.classes = classes;
        }
    }
}
