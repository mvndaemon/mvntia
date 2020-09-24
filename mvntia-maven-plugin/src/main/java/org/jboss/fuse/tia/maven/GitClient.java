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

import java.io.IOException;
import java.io.Writer;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.concurrent.CountDownLatch;
import java.util.logging.Level;
import java.util.logging.LogManager;
import java.util.stream.Collectors;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import org.jboss.fuse.tia.agent.Client;
import org.jboss.fuse.tia.reports.GitStorage;
import org.jboss.fuse.tia.reports.ReportStatus;
import org.jboss.fuse.tia.reports.TestReport;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class GitClient implements Client {

    private static final Logger LOGGER = LoggerFactory.getLogger(GitClient.class);

    Gson gson;
    GitStorage storage;
    CountDownLatch initialized = new CountDownLatch(1);
    Set<String> reactorDeps;

    ReportStatus status;
    Map<String, TestReport> reports;
    Map<String, TestReport> disabledTests;

    public GitClient(String executionDir, Set<String> deps) throws IOException {
        gson = new GsonBuilder().create();
        storage = new GitStorage(executionDir);
        reactorDeps = deps;
        this.initialize();
    }

    public void initialize() {
        status = ReportStatus.DIRTY;
        reports = new TreeMap<>();
        disabledTests = new TreeMap<>();
        try {
            // Check status and prepare
            status = storage.getStatus();
            if (status == ReportStatus.NO_COMMIT) {
                LOGGER.warn("Git not set up propertly, ignoring TIA...");
                return;
            } else if (status == ReportStatus.DIRTY) {
                LOGGER.warn("Git is dirty, TIA results won't be stored...");
            }
            // Load existing test reports
            String notes = storage.getNotes();
            TestReport[] loaded = gson.fromJson(notes, TestReport[].class);
            if (loaded != null) {
                for (TestReport report : loaded) {
                    reports.put(report.getTestMethodId(), report);
                }
            }
            // Find out which tests can be skipped
            if (!reports.isEmpty()) {
                Set<String> files = storage.getChangedAndCommittedFiles();
                files.addAll(storage.getFilesWithUntrackedChanges());
                // disabled tests are those which are not impacted by any modified files
                reports.values().stream()
                        .filter(report -> !report.isImpactedBy(files))
                        .forEach(report -> disabledTests.put(report.getTestMethodId(), report));
            }
        } catch (Exception e) {
            if (LOGGER.isDebugEnabled()) {
                LOGGER.warn("Unable to load test reports: " + e.toString(), e);
            } else {
                LOGGER.warn("Unable to load test reports: " + e.toString());
            }
        } finally {
            initialized.countDown();
        }
    }

    public Set<String> reactorDeps() {
        try {
            initialized.await();
            return reactorDeps;
        } catch (Exception e) {
            throw new RuntimeException("Unable to retrieve reactor dependencies", e);
        }
    }

    public Set<String> disabledTests() {
        try {
            initialized.await();
            return disabledTests.values()
                    .stream().map(TestReport::getTestClass).collect(Collectors.toSet());
        } catch (Exception e) {
            throw new RuntimeException("Unable to retrieve disabled tests", e);
        }
    }

    public void addReport(String test, String method, Set<String> classes) {
        try {
            initialized.await();
            TestReport report = new TestReport(test, method, classes);
            reports.put(report.getTestMethodId(), report);
        } catch (Exception e) {
            throw new RuntimeException("Error adding report", e);
        }
    }

    public void writeReports() {
        try {
            initialized.await();
            if (status == ReportStatus.CLEAN) {
                try (Writer writer = storage.buildWriter()) {
                    writer.write(gson.toJson(reports.values()));
                }
            }
        } catch (Exception e) {
            LOGGER.error("Error writing reports", e);
        }
    }

    public void log(String level, String message) {
        try {
            initialized.await();
            Level lvl = Level.parse(level);
            LogManager.getLogManager().getLogger(Client.class.getName())
                    .log(lvl, message);
        } catch (Exception e) {
            throw new RuntimeException("Error logging", e);
        }
    }


}
