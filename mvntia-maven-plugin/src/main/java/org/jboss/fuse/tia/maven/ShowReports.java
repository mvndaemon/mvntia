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
import java.nio.file.Files;
import java.nio.file.Paths;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonElement;
import com.google.gson.JsonParser;
import org.apache.maven.plugins.annotations.LifecyclePhase;
import org.apache.maven.plugins.annotations.Mojo;
import org.apache.maven.plugins.annotations.Parameter;
import org.apache.maven.plugins.annotations.ResolutionScope;
import org.jboss.fuse.tia.reports.Reports;
import org.jboss.fuse.tia.reports.Storage;

@Mojo(name = "show-reports", defaultPhase = LifecyclePhase.INITIALIZE,
        requiresDependencyResolution = ResolutionScope.RUNTIME, threadSafe = true)
public class ShowReports extends AbstractTiaMojo {

    @Parameter(property = "mvntia.file")
    String file;

    public final void doExecute() throws Exception {
        String notes = readNotes(createStorage());
        if (notes == null) {
            getLog().warn("No report available in this branch");
            return;
        }
        if (file != null) {
            Files.writeString(Paths.get(file), notes);
        } else {
            System.out.println(notes);
        }
    }

    public String readNotes(Storage storage) throws IOException {
        Storage.State state = storage.getState();
        String notes = state != null ? state.note : null;
        if (notes != null && !notes.trim().startsWith("{")) {
            notes = Reports.uncompress(notes);
        }
        if (notes != null && !notes.isBlank()) {
            JsonElement e = JsonParser.parseString(notes);
            Gson gson = new GsonBuilder().setPrettyPrinting().create();
            notes = gson.toJson(e);
        }
        return notes;
    }

}
