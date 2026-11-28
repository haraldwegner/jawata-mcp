package org.jawata.mcp.access;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** 28h z31: which command lines run more than a single test. */
class TestCommandsTest {

    @Test
    @DisplayName("28h z31: a build with its tests, a suite script and an unfiltered test command run more than a single test")
    void widerRunsAreRecognised() {
        List<String> missed = new ArrayList<>();
        for (String command : List.of("mvn test", "./mvnw -o -q -f build/pom.xml clean install", "cd app && mvn verify", "timeout 600 mvn -B package",
                "bash build/run-suite.sh 4 --full", "build/release-gate.sh", "./gradlew test", "gradle build", "cargo test --offline", "npm test",
                "npm run test", "pytest", "go test ./...", "node --test", "java -jar jawata.jar -runTests", "bash -c 'mvn test'",
                "/x/jawata-hook-guard bracket --cd /r --out /o -- mvn test", "echo start; cargo test")) {
            if (!TestCommands.runsMoreThanOneTest(command)) {
                missed.add(command);
            }
        }
        assertEquals(List.of(), missed);
    }

    @Test
    @DisplayName("28h z31: a single test, a build that skips the tests and a command that only mentions a test run do not")
    void singleRunsAndOtherCommandsPass() {
        List<String> refused = new ArrayList<>();
        for (String command : List.of("mvn -Dtest=FooTest test", "mvn clean package -DskipTests", "mvn -B compile", "./gradlew test --tests FooTest",
                "cargo test --offline the_one_test", "cargo build", "npm run build", "pytest tests/test_one.py", "go test ./pkg -run TestOne",
                "node --test test/one.test.mjs", "java -Djawata.test.classlist=/tmp/x -jar jawata.jar -runTests", "echo mvn test",
                "git commit -m 'mvn test passes'", "ls build/run-suite.sh.bak")) {
            if (TestCommands.runsMoreThanOneTest(command)) {
                refused.add(command);
            }
        }
        assertEquals(List.of(), refused);
    }
}
