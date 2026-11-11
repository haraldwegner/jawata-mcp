package org.jawata.mcp.execution;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Supplier;
import java.util.logging.Logger;

import org.jawata.mcp.engine.PromptSource;

/**
 * The seat files of one folder, served as MCP prompts (Sprint 28h, S14, D21).
 *
 * <p>A host that lists prompts shows each seat the person has not switched off, so a seat added to jawata appears in a host with no change
 * to the host, and the per-client command files stay the fallback for the clients that do not list prompts. The folder is the one the
 * scheduler reads ({@link ScheduledHires#SEATS_SETTING}) and it is read through the same reader ({@link SeatFiles}); the folder is asked
 * for and read again on every call, so a seat switched off ({@code enabled: false}) or written since the last call is seen at once.</p>
 *
 * <p>The prompt's name is the seat's {@code command:} when its file declares one, because that is the word a person types in a client that
 * has the seat as a command, and the seat's name otherwise. The title always carries the seat's name, so a person who knows the seat by
 * either word finds it. The text is the seat's stance, everything below its frontmatter.</p>
 */
public final class SeatPrompts implements PromptSource {

    private static final Logger log = Logger.getLogger(SeatPrompts.class.getName());

    private final Supplier<Optional<Path>> folder;

    /**
     * @param folder where the seat files are now; empty when no folder is set, which offers no prompt
     */
    public SeatPrompts(Supplier<Optional<Path>> folder) {
        this.folder = folder;
    }

    @Override
    public List<Prompt> list() {
        List<Prompt> prompts = new ArrayList<>();
        for (Offered offered : offered().values()) {
            prompts.add(offered.prompt());
        }
        return prompts;
    }

    @Override
    public Optional<String> text(String name) {
        Offered found = offered().get(name);
        return found == null ? Optional.empty() : Optional.of(found.text());
    }

    /** The brain a seat's own file names, when it names one (D53 (j)). */
    public Optional<String> brainNamedBy(String seatName) {
        return namedBy(seatName, "brain");
    }

    /** The model a seat's own file names beside its brain, when it names one (D56 (f)): the default the person set in the studio's Seats view. */
    public Optional<String> modelNamedBy(String seatName) {
        return namedBy(seatName, "model");
    }

    private Optional<String> namedBy(String seatName, String key) {
        return folder.get().flatMap(where -> SeatFiles.read(where).seats().stream().filter(seat -> seatName.equals(seat.name())).findFirst())
            .map(seat -> seat.front().get(key)).filter(value -> value != null && !value.isBlank()).map(String::strip);
    }

    /** The seats a person starts by typing their word (D53 (d)): those whose file does not say {@code typed: false}. */
    public List<Prompt> typedByAPerson() {
        List<Prompt> prompts = new ArrayList<>();
        for (Offered offered : offered().values()) {
            if (offered.typed()) {
                prompts.add(offered.prompt());
            }
        }
        return prompts;
    }

    /** A seat that is on offer: what the list shows of it, what a get returns, and whether a person starts it by typing. */
    private record Offered(Prompt prompt, String text, boolean typed) {
    }

    /** The seats on offer now, by prompt name, in the order of their files. A name two seats claim goes to the first, and the log says so. */
    private Map<String, Offered> offered() {
        Map<String, Offered> offered = new LinkedHashMap<>();
        Optional<Path> where = folder.get();
        if (where.isEmpty()) {
            return offered;
        }
        SeatFiles.Listing listing = SeatFiles.read(where.get());
        for (String line : listing.unreadable()) {
            log.warning(() -> "a seat file could not be read, so it is NOT offered as a prompt: " + line);
        }
        for (SeatFiles.SeatFile seat : listing.seats()) {
            try {
                if (!Frontmatter.readSwitch("enabled", seat.front().get("enabled"), true)) {
                    continue;
                }
            } catch (RuntimeException e) {
                log.warning(() -> seat.file().getFileName() + ": " + e.getMessage() + ", so it is NOT offered as a prompt");
                continue;
            }
            String stance = seat.body().strip();
            if (stance.isEmpty()) {
                log.warning(() -> seat.file().getFileName() + " has no text below its frontmatter, so it is NOT offered as a prompt");
                continue;
            }
            String command = Frontmatter.emptyToNull(seat.front().get("command"));
            String name = command == null ? seat.name() : command;
            String title = command == null ? seat.name() : seat.name() + " (/" + command + ")";
            String purpose = Frontmatter.emptyToNull(seat.front().get("purpose"));
            Offered prompt = new Offered(new Prompt(name, title, purpose == null ? "" : purpose), stance, Frontmatter.readSwitch("typed", seat.front().get("typed"), true));
            if (offered.putIfAbsent(name, prompt) != null) {
                log.warning(() -> seat.file().getFileName() + " claims the prompt name '" + name + "' that another seat already has, so it is NOT offered");
            }
        }
        return offered;
    }
}
