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

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import java.util.stream.StreamSupport;
import java.util.zip.Deflater;
import java.util.zip.DeflaterOutputStream;
import java.util.zip.Inflater;
import java.util.zip.InflaterInputStream;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.apache.maven.plugin.logging.Log;
import org.codehaus.plexus.util.IOUtil;
import org.jboss.fuse.tia.agent.Client;
import org.jboss.fuse.tia.reports.GitStorage;
import org.jboss.fuse.tia.reports.ReportStatus;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class GitClient implements Client {

    private static final Logger LOGGER = LoggerFactory.getLogger(GitClient.class);

    Gson gson;
    GitStorage storage;
    CountDownLatch initialized = new CountDownLatch(1);
    Log logger;

    ReportStatus status;
    Set<String> modified;
    final Map<String, Map<String, Set<String>>> reports = new TreeMap<>();
    final Map<String, Map<String, Set<String>>> temporary = new HashMap<>();

    public GitClient(String executionDir, Log log) throws IOException {
        gson = new GsonBuilder().create();
        storage = new GitStorage(executionDir);
        logger = log;
        this.initialize();
    }

    public void initialize() {
        status = ReportStatus.DIRTY;
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
            synchronized (reports) {
                reports.putAll(readNotes());
            }
            // Load modified files
            modified = new TreeSet<>();
            modified.addAll(storage.getChangedAndCommittedFiles());
            modified.addAll(storage.getFilesWithUntrackedChanges());
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

    /**
     * Resolves if any of the referenced classes in the test report (and the test itself) are
     * impacted / referenced from a list of modified files.
     *
     * @param modifiedFiles to consider
     * @return if the test needs to be re-executed because it is impacted by a change.
     */
    public boolean isImpactedBy(String testClass, Set<String> referencedClasses, Set<String> modifiedFiles) {
        return modifiedFiles.stream()
                .anyMatch(file -> !file.endsWith(".class")
                        &&
                        (matchesWithFile(testClass, file) ||
                                referencedClasses.stream().anyMatch(clazz -> matchesWithFile(clazz, file))));
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
        if (index > -1) {
            return statusFile.substring(0, index);
        }
        return statusFile;
    }

    private String getParentClassName(String className) {
        int innerClassIndex = className.indexOf("$");
        if (innerClassIndex > -1) {
            return className.substring(0, innerClassIndex);
        }
        return className;
    }

    public Set<String> disabledTests(String projectId) {
        try {
            initialized.await();
            // Find out which tests can be skipped
            // disabled tests are those which are not impacted by any modified files
            Set<String> disabled;
            synchronized (reports) {
                disabled = reports.computeIfAbsent(projectId, p -> new TreeMap<>()).entrySet().stream()
                        .filter(e -> !isImpactedBy(e.getKey(), e.getValue(), modified))
                        .map(Map.Entry::getKey)
                        .collect(Collectors.toSet());
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

    public void writeReport(String projectId) {
        try {
            initialized.await();
            if (status == ReportStatus.CLEAN) {
                // Read notes
                Map<String, Set<String>> rep;
                synchronized (temporary) {
                    rep = temporary.remove(projectId);
                }
                if (rep != null) {
                    String str;
                    synchronized (reports) {
                        Map<String, Set<String>> newRep = reports.computeIfAbsent(projectId, p -> new TreeMap<>());
                        rep.entrySet().forEach(e -> newRep.computeIfAbsent(e.getKey(), s -> new HashSet<>())
                                .addAll(e.getValue()));
                        str = writeReports(reports);
                    }
                    try (Writer writer = storage.buildWriter()) {
                        writer.write(str);
                    }
                    LOGGER.debug("mvntia::writeReport({}) => {} bytes written", projectId, str.length());
                }
            } else {
                LOGGER.debug("mvntia::writeReport({}) => skipped as git repository not clean", projectId);
            }
        } catch (Exception e) {
            LOGGER.error("Error writing reports", e);
        }
    }

    private Map<String, Map<String, Set<String>>> readNotes() throws IOException {
        String notes = storage.getNotes();
        return loadReports(notes);
    }

    public static String writeReports(Map<String, ? extends Map<String, ? extends Collection<String>>> reports) throws IOException {
        Map<String, Long> counts = reports.values().stream().map(Map::values)
                .flatMap(Collection::stream)
                .flatMap(Collection::stream)
                .collect(Collectors.groupingBy(s -> s, Collectors.counting()));

        String result;
        // If we reach 1000 times the same class, switch to dictionary based
        if (counts.values().stream().anyMatch(l -> l > 1000)) {
            AtomicInteger index = new AtomicInteger();
            Map<String, Object> output = new LinkedHashMap<>();
            Map<String, String> classes = new TreeMap<>();
            Map<String, String> revClasses = new TreeMap<>();
            counts.keySet().stream()
                    .sorted(Comparator.comparingLong(counts::get).reversed())
                    .distinct()
                    .forEach(s -> {
                        String v = Integer.toHexString(index.incrementAndGet());
                        revClasses.put(s, v);
                        classes.put(v, s);
                    });
            output.put("classes", classes);
            for (Map.Entry<String, ? extends Map<String, ? extends Collection<String>>> entry : reports.entrySet()) {
                String module = entry.getKey();
                Map<String, String> tests = new TreeMap<>();
                for (Map.Entry<String, ? extends Collection<String>> entry2 : entry.getValue().entrySet()) {
                    String test = entry2.getKey();
                    Collection<String> refs = entry2.getValue();
                    String sref = refs.stream().map(revClasses::get)
                            .sorted(Comparator.comparing(String::length).thenComparing(Comparator.naturalOrder()))
                            .distinct().collect(Collectors.joining(" "));
                    tests.put(test, sref);
                }
                output.put(module, tests);
            }
            result = new Gson().toJson(output);
        } else {
            result = new Gson().toJson(reports);
        }

        if (result.length() > 100_000) {
            result = compress(result);
        }
        return result;
    }

    public static Map<String, Map<String, Set<String>>> loadReports(String notes) throws IOException {
        if (notes == null || notes.isBlank()) {
            return new LinkedHashMap<>();
        }
        // if not empty and not starting with '{', assume base64+compressed
        if (!notes.trim().startsWith("{")) {
            notes = uncompress(notes);
        }
        JsonObject element = JsonParser.parseString(notes).getAsJsonObject();
        if (element.has("classes")) {
            Map<String, String> dict = element.remove("classes").getAsJsonObject().entrySet().stream()
                    .collect(Collectors.toMap(Map.Entry::getKey, e -> e.getValue().getAsString()));
            Map<String, Map<String, Set<String>>> reports = element.entrySet().stream().collect(Collectors.toMap(
                    Map.Entry::getKey,
                    e -> e.getValue().getAsJsonObject().entrySet().stream().collect(Collectors.toMap(
                                Map.Entry::getKey,
                                e2 -> {
                                    String s = e2.getValue().getAsString();
                                    if (s.isBlank()) {
                                        return new HashSet<>();
                                    } else {
                                        String[] ss = s.split(" ");
                                        return Stream.of(ss).map(dict::get).sorted().collect(Collectors.toSet());
                                    }
                                }))));
            return reports;
        } else {
            Map<String, Map<String, Set<String>>> reports = element.entrySet().stream().collect(Collectors.toMap(
                    Map.Entry::getKey,
                    e -> e.getValue().getAsJsonObject().entrySet().stream().collect(Collectors.toMap(
                            Map.Entry::getKey,
                            e2 -> StreamSupport.stream(e2.getValue().getAsJsonArray().spliterator(), false)
                                    .map(JsonElement::getAsString)
                                    .sorted().collect(Collectors.toSet())))));
            return reports;
        }
    }

    public static String compress(String notes) throws IOException {
        byte[] data = notes.getBytes(StandardCharsets.UTF_8);
        Deflater deflater = new Deflater(Deflater.BEST_COMPRESSION, true);
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        try (OutputStream os = new DeflaterOutputStream(Base64.getMimeEncoder().wrap(baos), deflater)) {
            os.write(data);
        }
        return baos.toString(StandardCharsets.UTF_8);
    }

    public static String uncompress(String notes) throws IOException {
        byte[] data = notes.getBytes(StandardCharsets.UTF_8);
        Inflater inflater = new Inflater(true);
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        try (InputStream is = new InflaterInputStream(Base64.getMimeDecoder()
                .wrap(new ByteArrayInputStream(data)), inflater)) {
            IOUtil.copy(is, baos);
        }
        return baos.toString(StandardCharsets.UTF_8);
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
