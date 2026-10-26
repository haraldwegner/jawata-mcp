package org.jawata.mcp.contract;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import org.jawata.spi.host.Notice;
import org.jawata.spi.host.PersonChannel;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * What every host promises when the engine speaks to the person (Sprint 28k, Stage 9; contract §4.2 and §18 ring 1): a notice at any
 * level is taken without throwing back into the engine, and a piece of work the person can see starts, reports its steps and ends the
 * same way, however many times it is told to end. How the host shows it (a log line, a status-bar job) is the host's own and is not
 * asserted here; that the engine can rely on the channel never failing is.
 */
public abstract class PersonChannelContract {

    /**
     * @return the channel under test, fresh for each test
     * @throws Exception when the implementation cannot be built
     */
    protected abstract PersonChannel channel() throws Exception;

    @Test
    @DisplayName("a notice at every level is taken without throwing")
    void aNoticeAtEveryLevelIsTaken() throws Exception {
        PersonChannel channel = channel();

        assertDoesNotThrow(() -> channel.tell(new Notice(Notice.Level.INFO, "something happened")));
        assertDoesNotThrow(() -> channel.tell(Notice.warning("something is degraded")));
        assertDoesNotThrow(() -> channel.tell(Notice.error("something failed")));
    }

    @Test
    @DisplayName("a piece of work starts, reports its steps and ends, and a second end is harmless")
    void aPieceOfWorkStartsStepsAndEnds() throws Exception {
        PersonChannel channel = channel();

        PersonChannel.Activity work = channel.begin("indexing the workspace");

        assertNotNull(work, "starting work answers an activity, never nothing");
        assertDoesNotThrow(() -> work.step("reading the first project"));
        assertDoesNotThrow(() -> work.step("reading the second project"));
        assertDoesNotThrow(work::close);
        assertDoesNotThrow(work::close);
    }

    @Test
    @DisplayName("work can be held in a try-with-resources and ends when the block does, even when the block fails")
    void workEndsWhenTheBlockDoes() throws Exception {
        PersonChannel channel = channel();

        assertDoesNotThrow(() -> {
            try (PersonChannel.Activity work = channel.begin("a job that fails")) {
                work.step("about to fail");
                throw new IllegalStateException("the job failed");
            } catch (IllegalStateException expected) {
                // the activity was closed on the way out, and closing did not replace the failure
            }
        });
    }
}
