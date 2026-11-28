package org.jawata.mcp.tools;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import org.jawata.mcp.execution.HireRegistry;
import org.jawata.mcp.models.CallerIdentity;
import org.jawata.mcp.models.ToolResponse;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

/**
 * 28h z31: a hired worker does not run a shell itself. It hands the command to jawata, which runs it, so jawata sees who runs what: the leads
 * have no shell, and a command that runs more than a single test is the test staff's.
 */
class ShellToolTest {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final boolean WINDOWS = System.getProperty("os.name", "").toLowerCase().startsWith("windows");

    @TempDir
    Path dir;

    private final CountDownLatch end = new CountDownLatch(1);
    private final List<Thread> held = new ArrayList<>();

    @AfterEach
    void endTheHires() throws InterruptedException {
        end.countDown();
        for (Thread thread : held) {
            thread.join(5_000);
        }
    }

    private ObjectNode command(String line) {
        return JSON.createObjectNode().put("command", line).put("folder", dir.toString());
    }

    private static ToolResponse asCaller(String callerId, ObjectNode args) {
        return CallerIdentity.supplied(callerId).speaking(() -> new ShellTool().execute(args));
    }

    private static Object field(ToolResponse response, String name) {
        return response.getData() instanceof Map<?, ?> data ? data.get(name) : null;
    }

    private static String code(ToolResponse response) {
        return response.isSuccess() ? "success" : response.getError().getCode();
    }

    /** A hire of this seat the registry holds as running, with this plan id, until the test ends. */
    private void running(String seat, String planId) throws InterruptedException {
        CountDownLatch registered = new CountDownLatch(1);
        Thread thread = new Thread(() -> {
            try (HireRegistry.Registration hire = HireRegistry.shared().begin(seat, "z31 " + planId, "")) {
                HireRegistry.shared().planOpened(planId);
                registered.countDown();
                end.await();
            } catch (InterruptedException ended) {
                Thread.currentThread().interrupt();
            }
        });
        thread.setDaemon(true);
        held.add(thread);
        thread.start();
        assertTrue(registered.await(5, TimeUnit.SECONDS), "the " + seat + " hire did not register as running");
    }

    @Test
    @DisplayName("28h z31: the command runs in the folder and its exit code and output are answered, a failing command too")
    void aCommandRunsInItsFolder() throws Exception {
        java.nio.file.Files.writeString(dir.resolve("z31-marker.txt"), "x");

        ToolResponse listed = new ShellTool().execute(command(WINDOWS ? "dir /b" : "ls"));
        ToolResponse failed = new ShellTool().execute(command("exit 3"));

        assertAll(
            () -> assertEquals("success", code(listed)),
            () -> assertTrue(String.valueOf(field(listed, "output")).contains("z31-marker.txt"), "not run in the folder: " + field(listed, "output")),
            () -> assertEquals(Integer.valueOf(0), field(listed, "exitCode")),
            () -> assertEquals("success", code(failed), "a command that ends non-zero is an answer, not a fault of the tool"),
            () -> assertEquals(Integer.valueOf(3), field(failed, "exitCode")));
    }

    @Test
    @DisplayName("28h z31: a command past its time limit is ended and said so; a missing folder and a missing command are refused")
    void aSlowCommandAndBadArgumentsAreRefused() {
        ToolResponse slow = new ShellTool().execute(command(WINDOWS ? "ping -n 20 127.0.0.1 >nul" : "sleep 20").put("timeoutSeconds", 1));
        ToolResponse noFolder = new ShellTool().execute(JSON.createObjectNode().put("command", "ls").put("folder", dir.resolve("none").toString()));
        ToolResponse noCommand = new ShellTool().execute(JSON.createObjectNode().put("folder", dir.toString()));

        assertAll(
            () -> assertEquals("TIMEOUT", code(slow)),
            () -> assertFalse(noFolder.isSuccess(), "a folder that is not there was accepted"),
            () -> assertFalse(noCommand.isSuccess(), "a call with no command was accepted"),
            () -> assertTrue(new ShellTool().changesCode(), "a shell can write, so a read-only seat must not be offered it"));
    }

    @Test
    @DisplayName("28h z31: main and the leads have no shell; a worker has one, and a caller the engine does not know is not refused")
    void theLeadsHaveNoShell() throws Exception {
        running("pm", "plan-z31-pm");
        running("chief-engineer", "plan-z31-chief");
        running("test-manager", "plan-z31-manager");
        running("developer", "plan-z31-developer");

        ToolResponse pm = asCaller("plan-z31-pm", command("echo hi"));
        ToolResponse chief = asCaller("plan-z31-chief", command("echo hi"));
        ToolResponse manager = asCaller("plan-z31-manager", command("echo hi"));
        ToolResponse developer = asCaller("plan-z31-developer", command("echo hi"));
        ToolResponse stranger = asCaller("z31-nobody-registered", command("echo hi"));

        assertAll(
            () -> assertEquals("SHELL_NOT_ALLOWED", code(pm)),
            () -> assertEquals("SHELL_NOT_ALLOWED", code(chief)),
            () -> assertEquals("SHELL_NOT_ALLOWED", code(manager)),
            () -> assertEquals("success", code(developer)),
            () -> assertEquals("success", code(stranger)));
    }

    @Test
    @DisplayName("28h z31: a hired worker's text search over Java is refused as in its own shell; a declared one runs, and a caller nobody knows is left alone")
    void aTextSearchOverJavaIsRefused() throws Exception {
        running("developer", "plan-z31j-developer");

        ToolResponse search = asCaller("plan-z31j-developer", command("grep -n foo Foo.java"));
        ToolResponse declared = asCaller("plan-z31j-developer", command("grep -n foo Foo.java # jawata-fallback: a comment line"));
        ToolResponse stranger = asCaller("z31j-nobody-registered", command("grep -n foo Foo.java"));

        assertAll(
            () -> assertEquals("JAVA_BY_JAWATA", code(search)),
            () -> assertEquals("success", code(declared)),
            () -> assertEquals("success", code(stranger)));
    }

    @Test
    @DisplayName("28h z31 piece 4: a hired worker's command that writes a Java file is refused unless it carries jawata-author:; other writes run")
    void aWriteToJavaIsRefused() throws Exception {
        org.junit.jupiter.api.Assumptions.assumeFalse(WINDOWS, "the commands are written for a POSIX shell");
        running("developer", "plan-z31a-developer");

        ToolResponse redirect = asCaller("plan-z31a-developer", command("echo x > Foo.java"));
        ToolResponse script = asCaller("plan-z31a-developer", command("python3 -c \"open('Foo.java','w')\""));
        ToolResponse inPlace = asCaller("plan-z31a-developer", command("sed -i s/a/b/ Foo.java"));
        ToolResponse copy = asCaller("plan-z31a-developer", command("cp notes.txt Foo.java"));
        ToolResponse read = asCaller("plan-z31a-developer", command("echo x > Foo.java # jawata-fallback: a read is not a write"));
        ToolResponse declared = asCaller("plan-z31a-developer", command("echo x > Foo.java # jawata-author: no tool writes this file"));
        ToolResponse text = asCaller("plan-z31a-developer", command("echo x > notes.txt"));
        ToolResponse looked = asCaller("plan-z31a-developer", command("ls Foo.java"));
        ToolResponse stranger = asCaller("z31a-nobody-registered", command("echo x > Foo.java"));

        assertAll(
            () -> assertEquals("JAVA_BY_JAWATA", code(redirect)),
            () -> assertEquals("JAVA_BY_JAWATA", code(script)),
            () -> assertEquals("JAVA_BY_JAWATA", code(inPlace)),
            () -> assertEquals("JAVA_BY_JAWATA", code(copy)),
            () -> assertEquals("JAVA_BY_JAWATA", code(read), "the read declaration does not open a write"),
            () -> assertEquals("success", code(declared)),
            () -> assertEquals("success", code(text)),
            () -> assertEquals("success", code(looked)),
            () -> assertEquals("success", code(stranger)));
    }

    @Test
    @DisplayName("28h z216: a hired worker's git commit or push in the shell is refused, however it is wrapped; other git commands run")
    void gitCommitAndPushAreRefused() throws Exception {
        running("developer", "plan-z216-developer");
        running("tester", "plan-z216-tester");

        ToolResponse commit = asCaller("plan-z216-developer", command("git commit -am done"));
        ToolResponse push = asCaller("plan-z216-tester", command("git push origin main"));
        ToolResponse elsewhere = asCaller("plan-z216-developer", command("git -C /tmp -c user.name=x commit -m done"));
        ToolResponse chained = asCaller("plan-z216-developer", command("git add -A && git commit -m done"));
        ToolResponse wrapped = asCaller("plan-z216-developer", command("bash -c 'git push'"));
        ToolResponse status = asCaller("plan-z216-developer", command("git status"));
        ToolResponse mentioned = asCaller("plan-z216-developer", command("echo git commit"));
        ToolResponse searched = asCaller("plan-z216-developer", command("git log --grep push"));
        ToolResponse stranger = asCaller("z216-nobody-registered", command("git push"));

        assertAll(
            () -> assertEquals("GIT_BY_JAWATA", code(commit)),
            () -> assertEquals("GIT_BY_JAWATA", code(push)),
            () -> assertEquals("GIT_BY_JAWATA", code(elsewhere)),
            () -> assertEquals("GIT_BY_JAWATA", code(chained)),
            () -> assertEquals("GIT_BY_JAWATA", code(wrapped)),
            () -> assertEquals("success", code(status)),
            () -> assertEquals("success", code(mentioned)),
            () -> assertEquals("success", code(searched)),
            () -> assertEquals("success", code(stranger)));
    }

    @Test
    @DisplayName("28h z31: a command that runs more than a single test is refused while the machine is short of memory; another command still runs")
    void aWiderRunNeedsFreeMemory() {
        ShellTool shortOfMemory = new ShellTool(() -> java.util.OptionalLong.of(10));

        ToolResponse suite = shortOfMemory.execute(command("bash build/run-suite.sh"));
        ToolResponse other = shortOfMemory.execute(command("echo hi"));

        assertAll(
            () -> assertEquals("TOO_LITTLE_MEMORY", code(suite)),
            () -> assertEquals("success", code(other)));
    }

    @Test
    @DisplayName("28h z31: a command that runs more than a single test is refused to a developer and a test writer; the tester runs it")
    void aWiderTestRunIsTheTestStaffs() throws Exception {
        running("developer", "plan-z31w-developer");
        running("test-writer", "plan-z31w-writer");
        running("tester", "plan-z31w-tester");

        ToolResponse suite = asCaller("plan-z31w-developer", command("bash build/run-suite.sh 4 --full"));
        ToolResponse single = asCaller("plan-z31w-developer", command("echo -Dtest=OneTest"));
        ToolResponse writer = asCaller("plan-z31w-writer", command("bash build/run-suite.sh 4 --full"));
        ToolResponse writerSingle = asCaller("plan-z31w-writer", command("echo -Dtest=OneTest"));
        ToolResponse tester = asCaller("plan-z31w-tester", command("bash build/run-suite.sh 4 --full"));

        assertAll(
            () -> assertEquals("PACKAGE_RUN_NOT_ALLOWED", code(suite)),
            () -> assertTrue(!suite.isSuccess() && suite.getError().getMessage().contains("developer"), "the refusal names the seat"),
            () -> assertEquals("success", code(single)),
            () -> assertEquals("PACKAGE_RUN_NOT_ALLOWED", code(writer), "the test writer started a full run on its own"),
            () -> assertEquals("success", code(writerSingle)),
            () -> assertEquals("success", code(tester)));
    }
}
