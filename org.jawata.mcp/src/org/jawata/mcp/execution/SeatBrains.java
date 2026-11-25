package org.jawata.mcp.execution;

import java.io.IOException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

/**
 * The one stored choice of brain and model for a seat, and the one answer a hire reads.
 * A seat file is not a choice. Nothing is filled in when the choice is missing, blank or unreadable.
 */
public final class SeatBrains {

    /** Where a person sets the choice. Said on every refusal. */
    public static final String WHERE = "Set it on the Seats page, or in the chat or Agent Control drop-down.";

    /** The system property that names the file the engine keeps the choice in. */
    public static final String FILE_PROPERTY = "jawata.seats.brains.file";

    /**
     * Brain and model taken together from one read.
     *
     * @param brain                  the brain's own name
     * @param model                  the model; never blank when a hire may start
     * @param modelTakenFromRegistry true when the entry named a brain and no model, and the registry named one
     */
    public record Choice(String brain, String model, boolean modelTakenFromRegistry) {
    }

    /** What a writer just stored, for every screen that shows the same entry. */
    public record Notice(String seat, String brain, String model) {
    }

    private static final AtomicReference<Store> OPEN = new AtomicReference<>();

    private SeatBrains() {
    }

    /**
     * The brain and model for {@code seat} from this one reading of {@code stored}.
     *
     * @param stored   the whole {@code jawata.seats.brains} value; null when it could not be read; empty when nothing is stored
     * @param registry the brain registry; a brain it does not offer is refused
     * @param seat     the seat being hired
     * @return the pair
     * @throws IllegalArgumentException naming the seat and where to set the choice
     */
    public static Choice choose(String stored, String registry, String seat) {
        if (seat == null || seat.isBlank()) {
            throw new IllegalArgumentException("a hire names no seat. " + WHERE);
        }
        String name = seat.strip();
        if (stored == null) {
            throw new IllegalArgumentException("the stored seat choices could not be read, so '" + name + "' was not hired. " + WHERE);
        }
        Map<String, String[]> entries;
        try {
            entries = parse(stored);
        } catch (IllegalArgumentException corrupt) {
            throw new IllegalArgumentException("the stored seat choices could not be read (" + corrupt.getMessage()
                + "), so '" + name + "' was not hired. " + WHERE);
        }
        String[] entry = entries.get(name);
        if (entry == null || entry[0] == null || entry[0].isBlank()) {
            throw new IllegalArgumentException("seat '" + name + "' has no brain stored. " + WHERE);
        }
        Optional<SeatHire.Brain> dialect = SeatHire.Brain.named(entry[0]);
        if (dialect.isEmpty()) {
            throw new IllegalArgumentException("seat '" + name + "' is set to run on '" + entry[0]
                + "', which is not a brain a hire can start. " + WHERE);
        }
        String brain = dialect.get().label();
        Optional<Hirer.Brain> offered = Hirer.brainIn(registry == null ? "" : registry, brain);
        if (offered.isEmpty()) {
            throw new IllegalArgumentException("seat '" + name + "' is set to run on '" + brain
                + "', which the brain registry does not offer. " + WHERE);
        }
        String model = modelOf(entry[1], registry, brain);
        if (model.isBlank()) {
            throw new IllegalArgumentException("seat '" + name + "' is set to run on '" + brain
                + "' with no model, and the brain registry names none for it. " + WHERE);
        }
        return new Choice(brain, model, entry[1] == null || entry[1].isBlank());
    }

    /**
     * The model a stored entry runs on: its own, else the one the registry names for its brain; empty when neither names one. A hire refuses an
     * empty answer, and the chat's principal then leaves the choice to the brain.
     */
    public static String modelOf(String entryModel, String registry, String brain) {
        if (entryModel != null && !entryModel.isBlank()) {
            return entryModel;
        }
        return Hirer.brainIn(registry == null ? "" : registry, brain).map(Hirer.Brain::model).orElse("");
    }

    /** The value with one seat replaced. Other seats are kept. */
    static String put(String stored, String seat, String brain, String model) {
        Map<String, String[]> entries = parse(stored == null ? "" : stored);
        entries.put(seat, new String[] {brain, model == null || model.isBlank() ? null : model});
        StringBuilder out = new StringBuilder();
        for (Map.Entry<String, String[]> each : entries.entrySet()) {
            if (out.length() > 0) {
                out.append(';');
            }
            out.append(encode(each.getKey())).append('=').append(encode(each.getValue()[0]));
            if (each.getValue()[1] != null && !each.getValue()[1].isBlank()) {
                out.append('/').append(encode(each.getValue()[1]));
            }
        }
        return out.toString();
    }

    /** A semicolon, slash, equals or backslash inside a name is stored escaped, so it is not a separator. */
    static String encode(String raw) {
        if (raw == null) {
            return "";
        }
        return raw.replace("\\", "\\\\").replace(";", "\\;").replace("/", "\\/").replace("=", "\\=");
    }

    static String decode(String raw) {
        StringBuilder out = new StringBuilder();
        for (int i = 0; i < raw.length(); i++) {
            char c = raw.charAt(i);
            if (c == '\\' && i + 1 < raw.length()) {
                out.append(raw.charAt(++i));
            } else {
                out.append(c);
            }
        }
        return out.toString();
    }

    /** One row per stored seat. A value that cannot be read is refused, not treated as empty. */
    public static java.util.List<Notice> listed(String stored) {
        if (stored == null) {
            throw new IllegalArgumentException("the stored seat choices could not be read. " + WHERE);
        }
        java.util.List<Notice> rows = new java.util.ArrayList<>();
        for (Map.Entry<String, String[]> each : parse(stored).entrySet()) {
            String model = each.getValue()[1];
            rows.add(new Notice(each.getKey(), each.getValue()[0], model == null ? "" : model));
        }
        return rows;
    }

    /** seat to {brain, model or null}. An entry that is not {@code seat=brain} makes the whole value unreadable. */
    static Map<String, String[]> parse(String stored) {
        Map<String, String[]> out = new LinkedHashMap<>();
        if (stored == null || stored.isBlank()) {
            return out;
        }
        for (String raw : splitOn(stored, ';')) {
            if (raw.isBlank()) {
                continue;
            }
            List<String> sides = splitOn(raw, '=');
            if (sides.size() != 2 || sides.get(0).isBlank()) {
                throw new IllegalArgumentException("an entry is not seat=brain");
            }
            String name = decode(sides.get(0)).strip();
            String rest = sides.get(1).strip();
            List<String> pair = splitOn(rest, '/');
            String brain = decode(pair.get(0)).strip();
            String model = pair.size() < 2 ? null : decode(String.join("/", pair.subList(1, pair.size()))).strip();
            if (model != null && model.isBlank()) {
                model = null;
            }
            out.put(name, new String[] {brain, model});
        }
        return out;
    }

    /** Splits on {@code mark} that is not escaped by a backslash. */
    private static List<String> splitOn(String raw, char mark) {
        List<String> parts = new java.util.ArrayList<>();
        StringBuilder current = new StringBuilder();
        for (int i = 0; i < raw.length(); i++) {
            char c = raw.charAt(i);
            if (c == '\\' && i + 1 < raw.length()) {
                current.append(c).append(raw.charAt(++i));
            } else if (c == mark) {
                parts.add(current.toString());
                current.setLength(0);
            } else {
                current.append(c);
            }
        }
        parts.add(current.toString());
        return parts;
    }

    /** The store this process hired from, when one was opened. */
    public static Optional<Store> installed() {
        return Optional.ofNullable(OPEN.get());
    }

    /** Opens {@code file} and makes it the store this process hires from. */
    public static Store install(Path file) {
        Store store = Store.open(file);
        OPEN.set(store);
        return store;
    }

    /** Drops the process store. Tests use it so one class does not leave a file behind for the next. */
    public static void uninstall() {
        OPEN.set(null);
    }

    /**
     * The file the running engine keeps the choice in: the named property, otherwise {@code seats-brains} under the data home.
     *
     * @param dataHome the data folder, used when the property is not set
     * @return the file
     */
    public static Path fileIn(Path dataHome) {
        String named = System.getProperty(FILE_PROPERTY);
        if (named != null && !named.isBlank()) {
            return Path.of(named);
        }
        Path home = dataHome == null ? Path.of(System.getProperty("user.home"), ".local", "share", "jawata") : dataHome;
        return home.resolve("seats-brains");
    }

    /** The choice on disk: one immutable text, replaced whole, written by renaming a temporary file into place. */
    public static final class Store {

        private final Path file;
        private final AtomicReference<String> text;
        private final Object lock = new Object();
        private final List<Consumer<Notice>> listeners = new CopyOnWriteArrayList<>();

        private Store(Path file, String text) {
            this.file = file;
            this.text = new AtomicReference<>(text);
        }

        /** Opens {@code file}. A missing file is an empty store. A file that cannot be read is an unreadable store, not an empty one. */
        public static Store open(Path file) {
            String body;
            if (file == null || !Files.exists(file)) {
                body = "";
            } else {
                try {
                    body = Files.readString(file);
                    parse(body);
                } catch (IOException | IllegalArgumentException unreadable) {
                    body = null;
                }
            }
            return new Store(file, body);
        }

        /** The whole value, one read. Null when the file could not be read. */
        public String text() {
            return text.get();
        }

        /** The pair for {@code seat} from one read of the value held now. */
        public Choice choose(String registry, String seat) {
            return SeatBrains.choose(text.get(), registry, seat);
        }

        /** Told after a save has been written and published. */
        public void listen(Consumer<Notice> listener) {
            if (listener != null) {
                listeners.add(listener);
            }
        }

        /**
         * Replaces one seat's entry, checks the brain and model against the registry first, writes the whole value, then publishes it.
         * Other seats are kept. A wrong brain or model is not stored.
         */
        public Notice save(String seat, String brain, String model, String registry) {
            Choice checked = SeatBrains.choose(encode(seat) + "=" + encode(brain) + (model == null || model.isBlank() ? "" : "/" + encode(model)), registry, seat);
            return publish(seat, checked);
        }

        private Notice publish(String seat, Choice checked) {
            String next;
            synchronized (lock) {
                // An unreadable file refuses a hire. A save replaces it: the old text could not be merged, so the new value starts from this seat.
                String current = text.get();
                if (current == null) {
                    current = "";
                }
                next = put(current, seat.strip(), checked.brain(), checked.model());
                write(file, next);
                text.set(next);
            }
            Notice notice = new Notice(seat.strip(), checked.brain(), checked.model());
            for (Consumer<Notice> listener : listeners) {
                listener.accept(notice);
            }
            return notice;
        }

        /** Writes {@code body} to a temporary file in the same folder and renames it over {@code file}. */
        static void write(Path file, String body) {
            try {
                Path parent = file.getParent();
                if (parent != null) {
                    Files.createDirectories(parent);
                }
                Path tmp = file.resolveSibling(file.getFileName().toString() + ".tmp");
                Files.writeString(tmp, body == null ? "" : body);
                try {
                    Files.move(tmp, file, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
                } catch (AtomicMoveNotSupportedException noAtomic) {
                    Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING);
                }
            } catch (IOException failed) {
                throw new IllegalStateException("the seat choices could not be saved: " + failed.getMessage(), failed);
            }
        }

        /**
         * Keeps the brain the person picked when no model is known for it (28h z78). Where the registry names a model this is {@link #save}; where
         * it names none the brain is stored alone, so the next conversation starts on it, and a hire of such an entry is still refused for lack of a
         * model. A brain the registry does not offer is not stored.
         */
        public Notice keepPick(String seat, String brain, String registry) {
            Optional<String> label = SeatHire.Brain.named(brain).map(SeatHire.Brain::label);
            Optional<Hirer.Brain> offered = label.flatMap(name -> Hirer.brainIn(registry == null ? "" : registry, name));
            if (offered.isEmpty()) {
                throw new IllegalArgumentException("'" + brain + "' is not a brain the registry offers. " + WHERE);
            }
            String named = offered.get().model();
            return named != null && !named.isBlank() ? save(seat, brain, "", registry) : publish(seat, new Choice(label.get(), "", false));
        }
    }
}
