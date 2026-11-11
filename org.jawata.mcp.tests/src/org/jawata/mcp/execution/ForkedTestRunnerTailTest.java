package org.jawata.mcp.execution;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.io.InputStream;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.CountDownLatch;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The output tail of a forked test run keeps what the run printed even when the run's last lines are still being read at the moment the
 * tail is asked for. Found on the CI runners of November 2026, which run about 2.4 times slower: RunTestsOutputTailTest's two DEBUG lines
 * arrived after the tail was asked for, and the tail came back empty, because it held the reader's lock while it waited for the reader.
 */
class ForkedTestRunnerTailTest {

    @Test
    @DisplayName("a line that arrives while the tail is being asked for is in the tail; the wait for the reader does not lock it out")
    void aLineThatArrivesWhileTheTailIsAskedForIsInTheTail() throws Exception {
        CountDownLatch release = new CountDownLatch(1);
        byte[] data = "12:04:12.002 [main] DEBUG org.example.Library -- a late line\n".getBytes(StandardCharsets.UTF_8);
        InputStream slow = new InputStream() {
            private int next;

            @Override
            public int read() throws IOException {
                if (next == 0) {
                    try {
                        release.await();
                    } catch (InterruptedException e) {
                        throw new IOException(e);
                    }
                }
                return next < data.length ? data[next++] : -1;
            }
        };
        Class<?> tailClass = Class.forName(ForkedTestRunner.class.getName() + "$StreamTail");
        Method consume = tailClass.getDeclaredMethod("consume", InputStream.class);
        consume.setAccessible(true);
        Object tail = consume.invoke(null, slow);
        Method read = tailClass.getDeclaredMethod("tail");
        read.setAccessible(true);

        Thread late = new Thread(() -> {
            try {
                Thread.sleep(200);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            release.countDown();
        });
        late.start();
        String text = (String) read.invoke(tail);
        late.join();

        assertTrue(text.contains("a late line"), "the line that arrived while the tail was asked for is missing: [" + text + "]");
    }
}
