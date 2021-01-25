package org.mvndaemon.mvnd.tests.it;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Arrays;
import java.util.List;
import java.util.function.Function;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import org.apache.maven.it.VerificationException;
import org.apache.maven.it.Verifier;
import org.eclipse.jgit.api.AddCommand;
import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.api.errors.GitAPIException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mvndaemon.mvnd.tests.support.TestUtils;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MultiModuleTest {

    Path projectDir;
    List<String> projectFiles;
    Git git;

    @BeforeEach
    void setup() throws IOException, GitAPIException {
        projectDir = Paths.get("target/mvntia-tests/testmm").toAbsolutePath();
        TestUtils.deleteDir(projectDir);
        TestUtils.copyDir(Paths.get("src/test/projects/testmm"), projectDir);

        projectFiles = Files.walk(projectDir).filter(Files::isRegularFile).map(projectDir::relativize).map(Path::toString).collect(Collectors.toList());
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
        assertEquals(Arrays.asList("no previous run", "no previous run"),
                newVerifier(disabledTestsLines()));

        // Second run
        assertEquals(Arrays.asList("1 tests disabled", "1 tests disabled"),
                newVerifier(disabledTestsLines()));

        // Change MyImpl.java
        editFile("testmm-m2/src/main/java/org/foo/impl/MyImpl.java",
                "return MyHelper.sayHello(who);", "return MyHelper.sayHello(who) + \"!\";");
        editFile("testmm-m2/src/test/java/org/foo/impl/MyImplTest.java",
                "assertEquals(\"Hello world!\",", "assertEquals(\"Hello world!!\",");
        assertEquals(Arrays.asList("1 tests disabled", "0 tests disabled"),
                newVerifier(disabledTestsLines()));
        assertEquals(Arrays.asList("1 tests disabled", "0 tests disabled"),
                newVerifier(disabledTestsLines()));

        // Commit
        addAndCommit("Modify MyImpl");
        assertEquals(Arrays.asList("1 tests disabled", "0 tests disabled"),
                newVerifier(disabledTestsLines()));
        assertEquals(Arrays.asList("1 tests disabled", "1 tests disabled"),
                newVerifier(disabledTestsLines()));

        // Change MyHelper
        editFile("testmm-m1/src/main/java/org/foo/util/MyHelper.java",
                "who", "name");
        assertEquals(Arrays.asList("0 tests disabled", "0 tests disabled"),
                newVerifier(disabledTestsLines()));
        assertEquals(Arrays.asList("0 tests disabled", "0 tests disabled"),
                newVerifier(disabledTestsLines()));

        // Commit
        addAndCommit("Modify MyHelper");
        assertEquals(Arrays.asList("0 tests disabled", "0 tests disabled"),
                newVerifier(disabledTestsLines()));
        assertEquals(Arrays.asList("1 tests disabled", "1 tests disabled"),
                newVerifier(disabledTestsLines()));

    }

    private void editFile(String file, String search, String replacement) throws IOException {
        Path myImplFile = projectDir.resolve(file);
        String myImplCode = Files.readString(myImplFile);
        String myImplModifiedCode = myImplCode.replaceAll("\\Q" + search + "\\E", replacement);
        Files.writeString(myImplFile, myImplModifiedCode);
    }

    private Function<Stream<String>, Stream<String>> disabledTestsLines() {
        return s -> s.filter(l -> l.contains("mvntia::disabledTests"))
                .map(l -> l.substring(l.indexOf(" => ") + " => ".length()));
    }

    private List<String> newVerifier(Function<Stream<String>, Stream<String>> logHandler) throws VerificationException, IOException {
        Path log = null;
        int nb = 0;
        while (log == null || Files.exists(log)) {
            log = projectDir.resolve("log-" + String.format("%02x", nb++) + ".txt");
        }
        newVerifier(log.getFileName().toString());
        return logHandler.apply(Files.lines(log)).collect(Collectors.toList());
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
