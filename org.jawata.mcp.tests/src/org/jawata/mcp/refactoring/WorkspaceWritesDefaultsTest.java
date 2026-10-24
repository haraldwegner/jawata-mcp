package org.jawata.mcp.refactoring;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import org.eclipse.core.runtime.NullProgressMonitor;
import org.eclipse.ltk.core.refactoring.Change;
import org.eclipse.ltk.core.refactoring.NullChange;
import org.jawata.spi.host.WorkspaceWrites;
import org.jawata.spi.host.WorkspaceWrites.WriteMode;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 28k E10b (contract §4.4): the two members the Eclipse host overrides are in the interface from the first release, so a host with no
 * editors — the headless one, and any implementation written before they existed — has to answer them exactly as it always has.
 */
class WorkspaceWritesDefaultsTest {

    @Test
    @DisplayName("a host that does not say performs a trial and a kept change exactly as it performs a change")
    void aHostWithNoUndoHistoryIgnoresTheMode() throws Exception {
        List<Change> performed = new ArrayList<>();
        WorkspaceWrites.Applied answer = new WorkspaceWrites.Applied(null, null);
        WorkspaceWrites plain = (change, monitor) -> {
            performed.add(change);
            return answer;
        };
        Change change = new NullChange("a change");

        assertSame(answer, plain.perform(change, WriteMode.TRIAL, new NullProgressMonitor()));
        assertSame(answer, plain.perform(change, WriteMode.ACCEPTED, new NullProgressMonitor()));

        assertEquals(List.of(change, change), performed, "both modes went through the one perform the host implements");
    }

    @Test
    @DisplayName("a host with no editors never refuses a write")
    void aHostWithNoEditorsNeverRefuses() {
        WorkspaceWrites plain = (change, monitor) -> new WorkspaceWrites.Applied(null, null);

        assertTrue(plain.refusal(Set.of()).isEmpty());
        assertTrue(WorkspaceWrites.DIRECT.refusal(List.of()).isEmpty());
    }

    @Test
    @DisplayName("the two modes are a trial that is judged and a change that is kept")
    void theTwoModes() {
        assertArrayEquals(new WriteMode[] {WriteMode.TRIAL, WriteMode.ACCEPTED}, WriteMode.values());
    }
}
