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
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Set;

import org.apache.maven.plugin.logging.SystemStreamLog;
import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.api.errors.GitAPIException;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

public class GitStorageTest {

    @Test
    public void testStatusLocalOnly() throws IOException, GitAPIException {
        Path local = Files.createTempDirectory("mvntia-");
        GitStorage storage = new GitStorage(local.toString());
        assertNull(storage.getState());

        Git git = Git.init().setDirectory(local.toFile()).call();
        assertNull(storage.getState());

        Path readme = local.resolve("readme.txt");
        Files.writeString(readme, "Readme file");
        assertNull(storage.getState());

        git.add().addFilepattern("readme.txt").call();
        assertNull(storage.getState());

        git.commit().setMessage("initial commit").call();
        assertEquals(new Storage.State(null, null, Set.of()), storage.getState());

        Files.writeString(readme, "Improved readme file");
        assertEquals(new Storage.State(null, null, Set.of("readme.txt")), storage.getState());

        git.add().addFilepattern("readme.txt").call();
        assertEquals(new Storage.State(null, null, Set.of("readme.txt")), storage.getState());

        git.commit().setMessage("second commit").call();
        assertEquals(new Storage.State(null, null, Set.of()), storage.getState());
    }

    @Test
    public void testStatusRemote() throws IOException, GitAPIException {
        Path remote = Files.createTempDirectory("mvntia-");
        Git gitr = Git.init().setDirectory(remote.toFile()).call();
        Path readme = remote.resolve("readme.txt");
        Files.writeString(readme, "Readme file");
        gitr.add().addFilepattern("readme.txt").call();
        gitr.commit().setMessage("initial commit").call();
        Files.writeString(readme, "Improved readme file");
        gitr.add().addFilepattern("readme.txt").call();
        gitr.commit().setMessage("second commit").call();

        Path local = Files.createTempDirectory("mvntia-");
        Git.cloneRepository().setURI(remote.toUri().toString()).setDirectory(local.toFile()).call();
        Git gitl = Git.init().setDirectory(local.toFile()).call();

        GitStorage storage = new GitStorage(local.toString());
        assertEquals(new Storage.State(null, null, Set.of()), storage.getState());

        Path src1 = local.resolve("src/org/foo/MyClass.java");
        Path test1 = local.resolve("test/org/foo/MyClassTest.java");
        Files.createDirectories(src1.getParent());
        Files.createDirectories(test1.getParent());
        Files.writeString(src1, "class org.foo.MyClass { }");
        Files.writeString(test1, "class org.foo.MyClassTest { }");
        assertEquals(new Storage.State(null, null, Set.of("src/org/foo/MyClass.java", "test/org/foo/MyClassTest.java")), storage.getState());

        gitl.add().addFilepattern(".").call();
        gitl.commit().setMessage("add a test").call();
        assertEquals(new Storage.State(null, null, Set.of()), storage.getState());

        GitClient client = new GitClient(storage, new SystemStreamLog());
        assertEquals(Set.of(), client.disabledTests("project", "digest"));
        client.addReport("project", "org.foo.MyClassTest", Set.of("org.foo.MyClass"));
        client.writeReport("project", "digest");

        assertEquals(new Storage.State("{\"digests\":{\"project\":\"digest\"},\"project\":{\"org.foo.MyClassTest\":[\"org.foo.MyClass\"]}}",
                Set.of(), Set.of()), storage.getState());
        client = new GitClient(storage, new SystemStreamLog());
        assertEquals(Set.of("org.foo.MyClassTest"), client.disabledTests("project", "digest"));

        Files.writeString(src1, "class org.foo.MyClass { foo }");
        assertEquals(new Storage.State("{\"digests\":{\"project\":\"digest\"},\"project\":{\"org.foo.MyClassTest\":[\"org.foo.MyClass\"]}}",
                Set.of(), Set.of("src/org/foo/MyClass.java")), storage.getState());
        client = new GitClient(storage, new SystemStreamLog());
        assertEquals(Set.of(), client.disabledTests("project", "digest"));

        gitl.add().addFilepattern(".").call();
        gitl.commit().setMessage("change class").call();
        assertEquals(new Storage.State("{\"digests\":{\"project\":\"digest\"},\"project\":{\"org.foo.MyClassTest\":[\"org.foo.MyClass\"]}}",
                Set.of("src/org/foo/MyClass.java"), Set.of()), storage.getState());
        client = new GitClient(storage, new SystemStreamLog());
        assertEquals(Set.of(), client.disabledTests("project", "digest"));
    }
}

