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

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import java.util.stream.StreamSupport;
import java.util.zip.Deflater;
import java.util.zip.DeflaterOutputStream;
import java.util.zip.Inflater;
import java.util.zip.InflaterInputStream;

import com.google.gson.Gson;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.codehaus.plexus.util.IOUtil;

public final class Reports {

    private Reports() {
    }

    public static String writeReports(Map<String, ? extends Map<String, ? extends Collection<String>>> reports, Map<String, String> digests) throws IOException {
        Map<String, Long> counts = reports.values().stream().map(Map::values)
                .flatMap(Collection::stream)
                .flatMap(Collection::stream)
                .collect(Collectors.groupingBy(s -> s, Collectors.counting()));

        String result;
        // If we reach 1000 times the same class, switch to dictionary based
        if (counts.values().stream().anyMatch(l -> l > 1000)) {
            AtomicInteger index = new AtomicInteger();
            Map<String, Object> output = new LinkedHashMap<>();
            Map<String, String> classes = new LinkedHashMap<>();
            Map<String, String> revClasses = new LinkedHashMap<>();
            counts.keySet().stream()
                    .sorted(Comparator.<String>comparingLong(counts::get).reversed().thenComparing(Comparator.naturalOrder()))
                    .distinct()
                    .forEach(s -> {
                        String v = Integer.toHexString(index.incrementAndGet());
                        revClasses.put(s, v);
                        classes.put(v, s);
                    });
            output.put("classes", classes);
            output.put("digests", digests);
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
            Map<String, Object> output = new LinkedHashMap<>();
            output.put("digests", digests);
            output.putAll(reports);
            result = new Gson().toJson(output);
        }

        if (result.length() > 100_000) {
            result = compress(result);
        }
        return result;
    }

    public static void loadReports(String notes, Map<String, Map<String, Set<String>>> reports, Map<String, String> digests) throws IOException {
        if (notes == null || notes.isBlank()) {
            return;
        }
        // if not empty and not starting with '{', assume base64+compressed
        if (!notes.trim().startsWith("{")) {
            notes = uncompress(notes);
        }
        JsonObject element = JsonParser.parseString(notes).getAsJsonObject();
        if (element.has("digests")) {
            element.remove("digests").getAsJsonObject().entrySet()
                    .forEach(e -> digests.put(e.getKey(), e.getValue().getAsString()));
        }
        Map<String, String> dict;
        if (element.has("classes")) {
            dict = element.remove("classes").getAsJsonObject().entrySet().stream()
                    .collect(Collectors.toMap(Map.Entry::getKey, e -> e.getValue().getAsString()));
        } else {
            dict = null;
        }
        for (Map.Entry<String, JsonElement> entry : element.entrySet()) {
            String key = entry.getKey();
            Map<String, Set<String>> value = entry.getValue().getAsJsonObject().entrySet().stream().collect(Collectors.toMap(
                    Map.Entry::getKey,
                    e2 -> getClasses(dict, e2.getValue())));
            reports.put(key, value);
        }
    }

    private static Set<String> getClasses(Map<String, String> dict, JsonElement v) {
        if (dict == null) {
            return StreamSupport.stream(v.getAsJsonArray().spliterator(), false)
                    .map(JsonElement::getAsString)
                    .sorted().collect(Collectors.toSet());
        } else {
            String s = v.getAsString();
            if (s.isBlank()) {
                return new HashSet<>();
            } else {
                String[] ss = s.split(" ");
                return Stream.of(ss).map(dict::get).sorted().collect(Collectors.toSet());
            }
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
}
