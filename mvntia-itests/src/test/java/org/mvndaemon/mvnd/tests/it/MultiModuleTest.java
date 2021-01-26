/*
 * Copyright 2021 the original author or authors.
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
package org.mvndaemon.mvnd.tests.it;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Arrays;
import java.util.List;
import java.util.function.Consumer;
import java.util.stream.Collectors;

import org.apache.maven.it.VerificationException;
import org.apache.maven.it.Verifier;
import org.eclipse.jgit.api.AddCommand;
import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.api.errors.GitAPIException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mvndaemon.mvnd.tests.support.TestUtils;

import static org.junit.jupiter.api.Assertions.assertEquals;

class MultiModuleTest {

    Path projectDir;
    List<String> projectFiles;
    Git git;

    @BeforeEach
    void setup() throws IOException, GitAPIException {
        projectDir = Paths.get("target/mvntia-tests/testmm").toAbsolutePath();
        TestUtils.deleteDir(projectDir);
        TestUtils.copyDir(Paths.get("src/test/projects/testmm"), projectDir);

        projectFiles = Files.walk(projectDir)
                .filter(Files::isRegularFile).map(projectDir::relativize)
                .map(Path::toString)
                .map(s -> s.replace(File.separatorChar, '/'))
                .collect(Collectors.toList());
        git = Git.init().setDirectory(projectDir.toFile()).call();
        String message = "Initial commit";
        addAndCommit(message);
    }

    private void addAndCommit(String message) throws GitAPIException {
        AddCommand add = git.add();
        projectFiles.forEach(add::addFilepattern);
        add.call();
        git.commit().setMessage(message).call();
    }

    @Test
    void testMultiModule() throws Exception {
        // First run
        newVerifier(disabledTestsLines("no previous run", "no previous run"));

        // Second run
        newVerifier(disabledTestsLines("1 tests disabled", "1 tests disabled"));

        // Change MyImpl.java
        editFile("testmm-m2/src/main/java/org/foo/impl/MyImpl.java",
                "return MyHelper.sayHello(who);", "return MyHelper.sayHello(who) + \"!\";");
        editFile("testmm-m2/src/test/java/org/foo/impl/MyImplTest.java",
                "assertEquals(\"Hello world!\",", "assertEquals(\"Hello world!!\",");
        newVerifier(disabledTestsLines("1 tests disabled", "0 tests disabled"));
        newVerifier(disabledTestsLines("1 tests disabled", "0 tests disabled"));

        // Commit
        addAndCommit("Modify MyImpl");
        newVerifier(disabledTestsLines("1 tests disabled", "0 tests disabled"));
        newVerifier(disabledTestsLines("1 tests disabled", "1 tests disabled"));

        // Change MyHelper
        editFile("testmm-m1/src/main/java/org/foo/util/MyHelper.java",
                "who", "name");
        newVerifier(disabledTestsLines("0 tests disabled", "0 tests disabled"));
        newVerifier(disabledTestsLines("0 tests disabled", "0 tests disabled"));

        // Commit
        addAndCommit("Modify MyHelper");
        newVerifier(disabledTestsLines("0 tests disabled", "0 tests disabled"));
        newVerifier(disabledTestsLines("1 tests disabled", "1 tests disabled"));
    }

    private void editFile(String file, String search, String replacement) throws IOException {
        Path myImplFile = projectDir.resolve(file);
        String myImplCode = Files.readString(myImplFile);
        String myImplModifiedCode = myImplCode.replaceAll("\\Q" + search + "\\E", replacement);
        Files.writeString(myImplFile, myImplModifiedCode);
    }

    private Consumer<List<String>> disabledTestsLines(String... expected) {
        return s -> {
            List<String> strings = Arrays.asList(expected);
            List<String> actual = s.stream().filter(l -> l.contains("mvntia::disabledTests"))
                    .map(l -> l.substring(l.indexOf(" => ") + " => ".length()))
                    .collect(Collectors.toList());
            assertEquals(strings, actual, () -> String.format("expected: <%s> but was: <%s>\n%s",
                    strings, actual, String.join("\n", s)));
        };
    }

    private void newVerifier(Consumer<List<String>> logHandler) throws VerificationException, IOException {
        Path log = null;
        int nb = 0;
        while (log == null || Files.exists(log)) {
            log = projectDir.resolve("log-" + String.format("%02x", nb++) + ".txt");
        }
        newVerifier(log.getFileName().toString());
        logHandler.accept(Files.readAllLines(log));
    }

    private void newVerifier(String logFileName) throws VerificationException {
        Verifier verifier;
        verifier = new Verifier(projectDir.toString());
        verifier.getSystemProperties().put("maven.multiModuleProjectDirectory", projectDir.toString());
        verifier.setForkJvm(false);
        verifier.setLogFileName(logFileName);
        verifier.executeGoal("test");
        verifier.verifyErrorFreeLog();
        verifier.resetStreams();
    }

}
