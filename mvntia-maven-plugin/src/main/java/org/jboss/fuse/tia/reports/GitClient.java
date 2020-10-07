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

import java.io.IOException;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import java.util.stream.StreamSupport;

import com.google.gson.JsonElement;
import org.apache.maven.plugin.logging.Log;
import org.jboss.fuse.tia.agent.Client;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class GitClient implements Client {

    private static final Logger LOGGER = LoggerFactory.getLogger(GitClient.class);

    Storage storage;
    CountDownLatch initialized = new CountDownLatch(1);
    Log logger;

    Status status;
    Set<String> modified;
    final Map<String, String> digests = new HashMap<>();
    final Map<String, Map<String, Set<String>>> reports = new TreeMap<>();
    final Map<String, Map<String, Set<String>>> temporary = new HashMap<>();

    public GitClient(Storage storage, Log logger) {
        this.storage = storage;
        this.logger = logger;
    }

    public void initialize() {
        status = Status.DIRTY;
        try {
            // Check status and prepare
            status = storage.getStatus();
            if (status == Status.NO_COMMIT) {
                LOGGER.warn("Git not set up propertly, ignoring TIA...");
                return;
            } else if (status == Status.DIRTY) {
                LOGGER.warn("Git is dirty, TIA results won't be stored...");
            }
            // Load existing test reports
            synchronized (reports) {
                String notes = storage.getNotes();
                Reports.loadReports(notes, reports, digests);
            }
            // Load modified files
            modified = new TreeSet<>(storage.getChangedFiles());
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

    public Set<String> disabledTests(Map<String, Set<String>> report) {
        return report.entrySet().stream()
                .filter(e -> !isImpactedBy(e.getKey(), e.getValue(), modified))
                .map(Map.Entry::getKey)
                .collect(Collectors.toSet());
    }

    /**
     * Resolves if any of the referenced classes in the test report (and the test itself) are
     * impacted / referenced from a list of modified files.
     *
     * @param modifiedFiles to consider
     * @return if the test needs to be re-executed because it is impacted by a change.
     */
    public boolean isImpactedBy(String testClass, Set<String> referencedClasses, Set<String> modifiedFiles) {
        return modifiedFiles.stream()
                .filter(file -> !file.endsWith(".class"))
                .anyMatch(file ->
                    Stream.concat(Stream.of(testClass), referencedClasses.stream())
                        .anyMatch(clazz -> matchesWithFile(clazz, file)));
    }

    private boolean matchesWithFile(String clazz, String statusFile) {
        String testFilePath = toFilePath(getParentClassName(clazz));
        return fileWithoutExtension(statusFile).endsWith(testFilePath);
    }

    private String toFilePath(String name) {
        return name.replaceAll("\\.", "/");
    }

    private String fileWithoutExtension(String statusFile) {
        int index = statusFile.lastIndexOf(".");
        return index > -1 ? statusFile.substring(0, index) : statusFile;
    }

    private String getParentClassName(String className) {
        int innerClassIndex = className.indexOf("$");
        return innerClassIndex > -1 ? className.substring(0, innerClassIndex) : className;
    }

    public Set<String> disabledTests(String projectId, String digest) {
        try {
            initialized.await();
            // Find out which tests can be skipped
            // disabled tests are those which are not impacted by any modified files
            Set<String> disabled;
            synchronized (reports) {
                Map<String, Set<String>> report = reports.computeIfAbsent(projectId, p -> new TreeMap<>());
                if (Objects.equals(digest, digests.get(projectId))) {
                    disabled = disabledTests(report);
                } else {
                    if (digests.containsKey(projectId)) {
                        logger.warn("Dependencies have changed, ignoring existing TIA data");
                    }
                    disabled = new HashSet<>();
                }
            }
            LOGGER.debug("mvntia::disabledTests({}) => {}", projectId, disabled);
            return disabled;
        } catch (Exception e) {
            throw new RuntimeException("Unable to retrieve disabled tests", e);
        }
    }

    public void addReport(String projectId, String test, List<String> classes) {
        try {
            initialized.await();
            LOGGER.debug("mvntia::addReport({}, {}, {})", projectId, test, classes);
            synchronized (temporary) {
                temporary
                        .computeIfAbsent(projectId, p -> new ConcurrentHashMap<>())
                        .computeIfAbsent(test, t -> new TreeSet<>())
                        .addAll(classes);
            }
        } catch (Exception e) {
            throw new RuntimeException("Error adding report", e);
        }
    }

    public void writeReport(String projectId, String digest) {
        try {
            initialized.await();
            if (status == Status.CLEAN) {
                // Read notes
                Map<String, Set<String>> rep;
                synchronized (temporary) {
                    rep = temporary.remove(projectId);
                }
                if (rep != null) {
                    String str;
                    synchronized (reports) {
                        Map<String, Set<String>> newRep = reports.computeIfAbsent(projectId, p -> new TreeMap<>());
                        rep.forEach((key, value) -> newRep.computeIfAbsent(key, s -> new HashSet<>()).addAll(value));
                        digests.put(projectId, digest);
                        str = Reports.writeReports(reports, digests);
                    }
                    storage.writeNotes(str);
                    LOGGER.debug("mvntia::writeReport({}) => {} bytes written", projectId, str.length());
                }
            } else {
                LOGGER.debug("mvntia::writeReport({}) => skipped as git repository not clean", projectId);
            }
        } catch (Exception e) {
            LOGGER.error("Error writing reports", e);
        }
    }

    public static Set<String> getClasses(JsonElement v) {
        return StreamSupport.stream(v.getAsJsonArray().spliterator(), false)
                .map(JsonElement::getAsString)
                .sorted().collect(Collectors.toSet());
    }

    public void log(String level, String message) {
        try {
            initialized.await();
            switch (level) {
                case "debug": logger.debug(message); break;
                case "info": logger.info(message); break;
                case "warn": logger.warn(message); break;
                case "error": logger.error(message); break;
            }
        } catch (Exception e) {
            LOGGER.error("Error logging", e);
        }
    }


}
