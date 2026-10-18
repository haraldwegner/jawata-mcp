package org.jawata.mcp.execution;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * A seat file's frontmatter: the {@code key: value} lines between the first two
 * {@code ---} lines, and the one place those values are read.
 *
 * <p>
 * It owns the map and the parsers over it, so a seat file's switches, lists,
 * numbers and durations are each read by ONE reader and every reader names the
 * key it refused. The parsers used to be private statics of {@link SeatHire}
 * over a bare map, with a second copy of the frontmatter parse for the one
 * switch that was read loosely; this record is the cure the architect's watch
 * of C10 named (S10-13), and a helper static was refused as the reflex.
 * </p>
 *
 * @param values the keys and their text, as written, in file order
 */
record Frontmatter(Map<String, String> values) {

	static String unquote(String value) {
		if (value.length() >= 2 && ((value.startsWith("\"") && value.endsWith("\""))
				|| (value.startsWith("'") && value.endsWith("'")))) {
			return value.substring(1, value.length() - 1);
		}
		return value;
	}

	static String emptyToNull(String value) {
		return value == null || value.isBlank() ? null : value;
	}

	/**
	 * A declared number, or ABSENT — never a substituted default.
	 *
	 * <p>
	 * A seat that writes {@code max_tool_steps: banana} has declared NO ceiling,
	 * and reading it as zero or as some default would silently bound a run by a
	 * number nobody wrote. The same reasoning the script dialect gives for a
	 * malformed cost line.
	 * </p>
	 */
	static Optional<Integer> integer(String value) {
		try {
			return value == null || value.isBlank() ? Optional.empty() : Optional.of(Integer.valueOf(value.strip()));
		} catch (NumberFormatException e) {
			return Optional.empty();
		}
	}

	static Optional<Double> decimal(String value) {
		try {
			return value == null || value.isBlank() ? Optional.empty() : Optional.of(Double.valueOf(value.strip()));
		} catch (NumberFormatException e) {
			return Optional.empty();
		}
	}

	static Optional<Duration> seconds(String value) {
		return integer(value).filter(s -> s > 0).map(Duration::ofSeconds);
	}

	/**
	 * An inline {@code [a, b, c]} list, which is how every shipped seat writes its
	 * tools.
	 */
	static List<String> list(String value) {
		if (value == null || value.isBlank()) {
			return List.of();
		}
		String inner = value.strip();
		if (inner.startsWith("[") && inner.endsWith("]")) {
			inner = inner.substring(1, inner.length() - 1);
		}
		List<String> items = new ArrayList<>();
		for (String part : inner.split(",")) {
			String item = unquote(part.strip());
			if (!item.isEmpty()) {
				items.add(item);
			}
		}
		return List.copyOf(items);
	}

	/**
	 * The frontmatter's scalar fields.
	 *
	 * <p>
	 * <b>A deliberate second implementation, and the reason is that the shared part
	 * is smaller than it looks.</b> {@code ExperienceMaintenance} also reads
	 * markdown frontmatter, and the architect seat's standing rule refuses a second
	 * implementation of a job the system already does. What those two readers share
	 * is finding the fences and splitting on the first colon — six lines. What they
	 * do NOT share is the value shapes: that one needs a YAML BLOCK list for its
	 * cues, this one needs an inline bracket list for its tools, and a common
	 * helper handing each a {@code Map<String,String>} would leave both re-parsing
	 * their own values anyway while adding a type neither owns. Extracting it would
	 * also mean editing a shipped reader in the knowledge package to serve a new
	 * feature in the execution package, for a six-line saving.
	 * </p>
	 *
	 * <p>
	 * Recorded as a declared duplicate rather than taken silently: if a THIRD
	 * reader ever wants frontmatter, that is the moment the shared type pays for
	 * itself, and this paragraph is the evidence for it.
	 * </p>
	 */
	static Map<String, String> frontmatter(String markdown) {
		Map<String, String> fields = new LinkedHashMap<>();
		if (markdown == null) {
			return fields;
		}
		String[] lines = markdown.split("\n", -1);
		if (lines.length == 0 || !lines[0].strip().equals("---")) {
			// No frontmatter at all. An empty map rather than a throw: the CALLER decides
			// which
			// absent field is fatal, and only the brain and the name are.
			return fields;
		}
		for (int i = 1; i < lines.length; i++) {
			String line = lines[i].strip();
			if (line.equals("---")) {
				break;
			}
			// A seat's frontmatter carries COMMENTS — every shipped seat has a multi-line
			// tier
			// justification in one, and three of those lines contain a colon. Skipped so
			// they do
			// not become junk keys like "# /main's, and wrong here costs a whole stage
			// rather than
			// one finding. No schedule".
			//
			// WHAT THIS SKIP DOES NOT BUY, stated because the first version of this comment
			// claimed it did: a comment CANNOT shadow a real field. `# model: x` splits at
			// its own
			// colon into the key "# model", which is not "model" — so without this skip the
			// junk
			// key is stored and the real field still wins. A mutation deleting the skip
			// left every
			// test green, which is how the false claim was found. The skip is kept because
			// junk
			// keys in a map a caller may iterate are worth not having, not because anything
			// downstream would otherwise break.
			if (line.startsWith("#") || line.isEmpty()) {
				continue;
			}
			int colon = line.indexOf(':');
			if (colon <= 0) {
				continue;
			}
			fields.put(line.substring(0, colon).strip(), unquote(line.substring(colon + 1).strip()));
		}
		return fields;
	}

	/**
	 * Everything below the frontmatter's closing fence — the seat's stance. A file
	 * with no frontmatter is ALL stance; a file whose fence never closes has none,
	 * because what follows the opening fence is then frontmatter that was never
	 * finished, not rules anybody wrote.
	 */
	static String body(String markdown) {
		if (markdown == null) {
			return "";
		}
		String[] lines = markdown.split("\n", -1);
		if (lines.length == 0 || !lines[0].strip().equals("---")) {
			return markdown;
		}
		for (int i = 1; i < lines.length; i++) {
			if (lines[i].strip().equals("---")) {
				return String.join("\n", Arrays.copyOfRange(lines, i + 1, lines.length));
			}
		}
		return "";
	}

	/**
	 * The ONE reader of a seat's on/off style switches, read STRICTLY:
	 * {@code changes_code}, {@code reads_files}, {@code accepts_uncontained_writes}
	 * and {@code enabled} all come through here, so a value that is neither
	 * {@code true} nor {@code false} is refused by name for every one of them and
	 * no switch is read more loosely than another. A switch that widens what a
	 * brain holds, or that turns a seat on, is not guessed from a misspelling:
	 * {@code yes} was silently {@code no} for the one switch that used to read
	 * loosely, which is the case this closes (S10-13).
	 *
	 * <p>
	 * {@code enabled} is the case with a different default. The studio writes
	 * {@code enabled: false} into a seat's file when the user switches the seat
	 * off, because the file is the one thing every reader opens (C11-6); absent
	 * means on, so it reads with {@code whenAbsent} true. The capability switches
	 * read false when absent, so an unattended run gets the narrower hire.
	 * </p>
	 *
	 * @param key        the frontmatter key, named in the refusal
	 * @param value      the text after the colon, or null when the seat does not
	 *                   say
	 * @param whenAbsent what a missing or blank value reads as
	 */
	static boolean readSwitch(String key, String value, boolean whenAbsent) {
		if (value == null || value.isBlank()) {
			return whenAbsent;
		}
		return switch (value.strip()) {
		case "true" -> true;
		case "false" -> false;
		default -> throw new IllegalArgumentException("seat declares " + key + " '" + value.strip()
				+ "': expected true or false. NOT read as either — a misspelled switch is refused"
				+ " rather than guessed");
		};
	}
}
