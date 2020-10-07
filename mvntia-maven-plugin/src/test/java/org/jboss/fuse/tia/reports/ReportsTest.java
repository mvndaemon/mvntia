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
import java.io.InputStreamReader;
import java.io.Reader;
import java.io.StringWriter;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;

import org.codehaus.plexus.util.IOUtil;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

public class ReportsTest {

    @Test
    public void testCompression() throws IOException {
        String notes = "foo bar";

        String compressed = Reports.compress(notes);
        String uncompressed = Reports.uncompress(compressed);
        assertEquals(notes, uncompressed);
    }

    @Test
    public void testReencode() throws IOException {
        String notes;
        try (Reader r = new InputStreamReader(getClass().getResourceAsStream("/notes.json"))) {
            StringWriter sw = new StringWriter();
            IOUtil.copy(r, sw);
            notes = sw.toString();
        }

        Map<String, Map<String, Set<String>>> e = new HashMap<>();
        Reports.loadReports(notes, e, new HashMap<>());
        String str = Reports.writeReports(e, new HashMap<>());

        Map<String, Map<String, Set<String>>> e2 = new HashMap<>();
        Reports.loadReports(str, e2, new HashMap<>());
        assertEquals(e, e2);
    }
}
