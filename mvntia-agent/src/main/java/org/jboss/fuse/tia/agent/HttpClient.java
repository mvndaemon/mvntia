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

import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.io.Reader;
import java.io.Writer;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Set;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

public class HttpClient implements Client {

    final int port;
    final Gson gson;

    public HttpClient(int port) {
        this.port = port;
        this.gson = new GsonBuilder().create();
    }

    @Override
    public Set<String> disabledTests(String project, String digest) {
        try {
            JsonObject req = new JsonObject();
            req.addProperty("request", "disabledTests");
            req.addProperty("project", project);
            req.addProperty("digest", digest);
            JsonObject rep = request(req);
            if (rep.has("error")) {
                throw new IOException(rep.get("error").toString());
            }
            String[] tests = gson.fromJson(rep.get("result"), String[].class);
            return Set.of(tests);
        } catch (IOException e) {
            e.printStackTrace();
            return null;
        }
    }

    @Override
    public void addReport(String project, String test, List<String> classes) {
        try {
            JsonObject req = new JsonObject();
            req.addProperty("request", "addReport");
            req.addProperty("project", project);
            req.addProperty("test", test);
            req.add("classes", gson.toJsonTree(classes));
            JsonObject rep = request(req);
            if (rep.has("error")) {
                throw new IOException(rep.get("error").toString());
            }
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    @Override
    public void writeReport(String project, String digest) {
        try {
            JsonObject req = new JsonObject();
            req.addProperty("request", "writeReport");
            req.addProperty("project", project);
            req.addProperty("digest", digest);
            JsonObject rep = request(req);
            if (rep.has("error")) {
                throw new IOException(rep.get("error").toString());
            }
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    @Override
    public void log(String level, String message) {
        try {
            JsonObject req = new JsonObject();
            req.addProperty("request", "log");
            req.addProperty("level", level);
            req.addProperty("message", message);
            JsonObject rep = request(req);
            if (rep.has("error")) {
                throw new IOException(rep.get("error").toString());
            }
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    protected JsonObject request(JsonObject request) throws IOException {
        URL url = new URL("http://localhost:" + port + "/");
        String req = gson.toJson(request);
        HttpURLConnection con = (HttpURLConnection) url.openConnection();
        con.setDoOutput(true);
        con.setRequestMethod("POST");
        con.setUseCaches(false);
        try (Writer w = new OutputStreamWriter(con.getOutputStream(), StandardCharsets.UTF_8)) {
            w.write(req);
        }
        try (Reader r = new InputStreamReader(con.getInputStream(), StandardCharsets.UTF_8)) {
            return JsonParser.parseReader(r).getAsJsonObject();
        }
    }
}
