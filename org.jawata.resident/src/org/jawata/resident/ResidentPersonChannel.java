package org.jawata.resident;

import org.jawata.spi.host.Notice;
import org.jawata.spi.host.PersonChannel;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * How the headless host reaches the person (Sprint 28k, contract §4.2): it writes to the process's log, which is where a person who
 * runs the engine alone looks. A notice goes out at the level it was told at; a piece of work is logged when it starts, at each step and
 * when it ends. Nothing is shown on a screen, because this host has none.
 */
public final class ResidentPersonChannel implements PersonChannel {

    private static final Logger log = LoggerFactory.getLogger(ResidentPersonChannel.class);

    @Override
    public void tell(Notice notice) {
        switch (notice.level()) {
        case ERROR -> log.error("{}", notice.text());
        case WARNING -> log.warn("{}", notice.text());
        default -> log.info("{}", notice.text());
        }
    }

    @Override
    public Activity begin(String label) {
        log.info("Started: {}", label);
        return new Activity() {
            @Override
            public void step(String what) {
                log.info("{}: {}", label, what);
            }

            @Override
            public void close() {
                log.info("Finished: {}", label);
            }
        };
    }
}
