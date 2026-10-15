package org.jawata.mcp.tools.verification;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import jdk.jfr.Configuration;
import jdk.jfr.Recording;
import org.jawata.mcp.models.ToolResponse;
import org.jawata.mcp.runtime.RuntimeArtifactStore;
import org.jawata.mcp.runtime.RuntimeSessionRegistry;
import org.jawata.mcp.runtime.profile.ExecutionSampleCount;
import org.jawata.mcp.tools.ProfileTool;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * C15-4 — {@code call_tree} over a recording with NO execution samples says the sampler caught
 * nothing, and does not send the reader to check the entry point's name.
 *
 * <p>The steering has two absence branches. One says samples exist but none passed through the
 * entry point, so the NAME or the method is implicated; HotspotTest reaches it with a class that
 * does not exist. The other says the recording holds no samples at all, so the entry point is
 * not implicated and the fix is to record differently. Nothing asserted that second branch: its
 * fixtures sample a live JVM, and none of them can be told to catch nothing.</p>
 *
 * <p>This one can, because the recording is made here. A recording with
 * {@code jdk.ExecutionSample} disabled is exactly the case the branch's own second half
 * describes — an {@code action=jfr_dump} artifact whose settings never enabled the sampler — and
 * it holds zero samples by construction rather than by luck. Every other event the default
 * settings record is still in it, so this is not an empty file standing in for one.</p>
 */
class ACallTreeOverNoSamplesSaysTheSamplerCaughtNothingTest {

    private static final ObjectMapper OM = new ObjectMapper();

    @TempDir
    Path runtimeDir;

    @Test
    @DisplayName("C15-4: a recording without execution samples is reported as the sampler catching nothing")
    void aRecordingWithNoSamplesIsNotBlamedOnTheEntryPoint() throws Exception {
        RuntimeArtifactStore store = new RuntimeArtifactStore(runtimeDir);
        String artifactId = store.newArtifactId("jfr-dump");
        Path jfr = store.createArtifactDir(artifactId).resolve("recording.jfr");

        try (Recording recording = new Recording(Configuration.getConfiguration("default"))) {
            recording.disable("jdk.ExecutionSample");
            recording.start();
            Thread.sleep(50);
            recording.stop();
            recording.dump(jfr);
        }
        store.writeManifest(artifactId, Map.of("kind", "jfr-dump"));
        assertEquals(0, ExecutionSampleCount.of(jfr),
            "the fixture itself: a recording with the sampler disabled holds no samples");

        ProfileTool profile = new ProfileTool(() -> null, new RuntimeSessionRegistry(), store);
        ObjectNode args = OM.createObjectNode();
        args.put("action", "call_tree");
        args.put("artifactId", artifactId);
        args.put("entryPoint", ACallTreeOverNoSamplesSaysTheSamplerCaughtNothingTest.class.getName());
        ToolResponse response = profile.execute(args);

        @SuppressWarnings("unchecked")
        Map<String, Object> data = (Map<String, Object>) response.getData();
        String steering = response.getMeta().getSteering();
        assertAll(
            () -> assertTrue(response.isSuccess(), () -> "an empty recording is an answer: " + data),
            () -> assertEquals(0L, ((Number) data.get("executionSamplesInRecording")).longValue(),
                () -> "the answer names the empty input: " + data),
            () -> assertTrue(steering.contains("holds NO stack-bearing execution samples"),
                () -> "the steering says the sampler caught nothing: " + steering),
            () -> assertTrue(steering.contains("jdk.ExecutionSample"),
                () -> "and names the setting a jfr_dump recording needs: " + steering),
            () -> assertFalse(steering.contains("the sampler WAS working"),
                () -> "and does not send the reader to the entry point's name: " + steering));
    }
}
