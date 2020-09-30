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

import java.io.BufferedReader;
import java.io.Closeable;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.io.Reader;
import java.io.Writer;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.Executors;
import java.util.stream.Collectors;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.jboss.fuse.tia.agent.Client;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class Server implements Closeable {

    private static final Logger LOGGER = LoggerFactory.getLogger(Server.class);

    Gson gson;
    Client client;
    HttpServer server;

    public Server(Client c) throws IOException  {
        gson = new GsonBuilder().create();
        client = c;
        server = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
        server.setExecutor(Executors.newFixedThreadPool(2));
        server.createContext("/", this::handle);
        server.start();
        LOGGER.info("MvnTIA server started");
    }

    public int getPort() {
        return server.getAddress().getPort();
    }

    @Override
    public void close() {
        server.stop(0);
    }

    void handle(HttpExchange exchange) throws IOException {
        try (Reader r  = new InputStreamReader(exchange.getRequestBody(), StandardCharsets.UTF_8)) {
            JsonObject response;
            int code = 200;
            try {
                String s = new BufferedReader(r).lines().collect(Collectors.joining(System.lineSeparator()));
                if (s.isEmpty() || s.isBlank()) {
                    code = 500;
                    response = new JsonObject();
                    response.addProperty("error", "Empty request");
                } else {
                    JsonObject request = JsonParser.parseString(s).getAsJsonObject();
                    if (!request.has("request")) {
                        response = new JsonObject();
                        response.addProperty("error", "Missing 'request' property");
                    } else {
                        String reqStr = request.get("request").getAsString();
                        switch (reqStr) {
                            case "disabledTests": {
                                JsonArray result = new JsonArray();
                                client.disabledTests(
                                        request.get("project").getAsString(),
                                        request.get("digest").getAsString()
                                ).forEach(result::add);
                                response = new JsonObject();
                                response.add("result", result);
                                break;
                            }
                            case "addReport":
                                client.addReport(
                                        request.get("project").getAsString(),
                                        request.get("test").getAsString(),
                                        List.of(gson.fromJson(request.get("classes"), String[].class))
                                );
                                response = new JsonObject();
                                response.addProperty("result", "ok");
                                break;
                            case "writeReport":
                                client.writeReport(request.get("project").getAsString(), request.get("digest").getAsString());
                                response = new JsonObject();
                                response.addProperty("result", "ok");
                                break;
                            case "log":
                                client.log(
                                        request.get("level").getAsString(),
                                        request.get("message").getAsString()
                                );
                                response = new JsonObject();
                                response.addProperty("result", "ok");
                                break;
                            default:
                                response = new JsonObject();
                                response.addProperty("error", "Unsupported request '" + s + "'");
                                break;
                        }
                    }
                }
            } catch (Exception e) {
                LOGGER.warn("Error processing request", e);
                code = 500;
                response = new JsonObject();
                response.addProperty("error", e.toString());
            }
            try {
                String res = gson.toJson(response);
                exchange.sendResponseHeaders(code, 0);
                try (Writer w = new OutputStreamWriter(exchange.getResponseBody(), StandardCharsets.UTF_8)) {
                    w.write(res);
                }
            } catch (IOException e) {
                LOGGER.error("Unable to write response", e);
                close();
            }
        }
    }

}
