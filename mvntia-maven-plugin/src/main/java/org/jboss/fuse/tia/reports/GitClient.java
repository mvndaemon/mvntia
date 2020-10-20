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

import java.util.Collection;
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

import org.apache.maven.plugin.logging.Log;
import org.jboss.fuse.tia.agent.Client;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;

public class GitClient implements Client {

    private static final Logger LOGGER = LoggerFactory.getLogger(GitClient.class);

    final Storage storage;
    final Log logger;
    final CountDownLatch initialized = new CountDownLatch(1);

    Storage.State state;
    final Map<String, String> digests = new HashMap<>();
    final Map<String, Map<String, Set<String>>> reports = new TreeMap<>();
    final Map<String, Map<String, Set<String>>> temporary = new HashMap<>();

    static final Map<String, Log> LOGGERS = new ConcurrentHashMap<>();

    public GitClient(Storage storage, Log logger) {
        this.storage = storage;
        this.logger = logger;
        initialize();
    }

    public void initialize() {
        state = null;
        try {
            // Check storage state
            state = storage.getState();
            if (state == null) {
                LOGGER.warn("Git not set up properly, ignoring TIA...");
                return;
            } else if (!state.uncommitted.isEmpty()) {
                LOGGER.warn("Git is dirty, TIA results won't be stored...");
            }
            // Load existing test reports
            int nbModified;
            int nbImpacted;
            synchronized (reports) {
                Reports.loadReports(state.note, reports, digests);
                // Load modified files
                Set<String> modified = new TreeSet<>();
                if (state.modified != null) {
                    modified.addAll(state.modified);
                }
                if (state.uncommitted != null) {
                    modified.addAll(state.uncommitted);
                }
                modified = modified.stream()
                        .filter(file -> file.endsWith(".java"))
                        .collect(Collectors.toSet());
                logger.debug("Modified files: " + modified);
                int countBefore = reports.values().stream().mapToInt(Map::size).sum();
                removeTestsImpactedBy(reports, modified);
                int countAfter = reports.values().stream().mapToInt(Map::size).sum();
                nbImpacted = countBefore - countAfter;
                nbModified = modified.size();
            }
            LOGGER.info(nbImpacted + " tests impacted by " + nbModified + " modified files");
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

    public void removeTestsImpactedBy(Map<String, Map<String, Set<String>>> reports, Set<String> modified) {
        Set<String> m = modified.stream()
                .map(this::fileWithoutExtension)
                .collect(Collectors.toSet());
        Map<String, String> toPath = new ConcurrentHashMap<>();
        reports.values().forEach(r -> r.entrySet()
                .removeIf(e -> isImpactedBy(e.getKey(), e.getValue(), m, toPath)));
    }

    /**
     * Resolves if any of the referenced classes in the test report (and the test itself) are
     * impacted / referenced from a list of modified files.
     *
     * @param modifiedFiles to consider
     * @return if the test needs to be re-executed because it is impacted by a change.
     */
    public boolean isImpactedBy(String testClass, Set<String> referencedClasses, Set<String> modifiedFiles, Map<String, String> toPath) {
        return modifiedFiles.stream()
                .anyMatch(file ->
                    Stream.concat(Stream.of(testClass), referencedClasses.stream())
                        .anyMatch(clazz -> matchesWithFile(clazz, file, toPath)));
    }

    private boolean matchesWithFile(String clazz, String statusFile, Map<String, String> toPath) {
        String testFilePath = toPath.computeIfAbsent(clazz, c -> toFilePath(getParentClassName(c)));
        return statusFile.endsWith(testFilePath);
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
            Log logger = getLog(projectId);
            // Find out which tests can be skipped
            // disabled tests are those which are not impacted by any modified files
            Set<String> disabled;
            synchronized (reports) {
                Map<String, Set<String>> report = reports.computeIfAbsent(projectId, p -> new TreeMap<>());
                String prevDigest = digests.get(projectId);
                if (prevDigest == null) {
                    disabled = new HashSet<>();
                    logger.info("mvntia::disabledTests(" + projectId + ") => no previous run");
                } else if (Objects.equals(digest, prevDigest)) {
                    disabled = report.keySet();
                    logger.info("mvntia::disabledTests(" + projectId + ") => " + disabled.size() + " tests disabled");
                } else {
                    disabled = new HashSet<>();
                    logger.info("mvntia::disabledTests(" + projectId + ") => dependencies have changed from [" + prevDigest + " ] to [" + digest + "]");
                }
            }
            return disabled;
        } catch (Exception e) {
            throw new RuntimeException("Unable to retrieve disabled tests", e);
        }
    }

    public void addReport(String projectId, String test, Collection<String> classes) {
        try {
            initialized.await();
            Log logger = getLog(projectId);
            logger.info("mvntia::addReport(" + projectId + ", " + test + ", [" + classes.size() + " classes])");
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
            Log logger = getLog(projectId);
            if (state.uncommitted.isEmpty()) {
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
                    logger.info("mvntia::writeReport(" + projectId + ") => " + str.length() + " bytes written");
                } else {
                    logger.info("mvntia::writeReport(" + projectId + ") => no report to write");
                }
            } else {
                logger.warn("mvntia::writeReport(" + projectId + ") => skipped as git repository not clean");
            }
        } catch (Exception e) {
            throw new RuntimeException("Error writing reports", e);
        }
    }

    public void log(String projectId, String level, String message) {
        try {
            initialized.await();
            Log logger = getLog(projectId);
            switch (level) {
                case "debug": logger.debug(message); break;
                case "info": logger.info(message); break;
                case "warn": logger.warn(message); break;
                case "error": logger.error(message); break;
            }
        } catch (Exception e) {
            throw new RuntimeException("Error logging", e);
        }
    }

    public static void setLogger(String projectId, Log logger) {
        LOGGERS.put(projectId, logger);
    }

    protected Log getLog(String projectId) {
        Log logger = LOGGERS.getOrDefault(projectId, this.logger);
        if (projectId != null && projectId.contains(":")) {
            MDC.put("maven.project.id", projectId.substring(projectId.indexOf(':') + 1));
        }
        return logger;
    }


}
