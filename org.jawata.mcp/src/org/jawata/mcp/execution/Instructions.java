package org.jawata.mcp.execution;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

/**
 * The instructions a PM or a chief engineer issues, read from its answer, and whether the worker ran
 * each one AS WRITTEN (the sprint spec, 2026-10-11: the PM is measured on "tokens spent per
 * instruction that ran as written (one tool, one call, no rework by the worker)").
 *
 * <p>An instruction is a block in the issuer's answer, the shape its seat file prescribes:</p>
 *
 * <pre>
 * INSTRUCTION 1
 * TOOL: rename_symbol
 * ARGUMENTS: {"symbol":"pkg.Type#member","newName":"x"}
 * </pre>
 *
 * <p>It <em>ran as written</em> when the worker made one call to that tool carrying exactly those
 * arguments and no further call for it (28h z117): the same call a second time, or a call of that tool
 * nobody wrote that acts on the same file, symbol or command (an edit: also on the same text), is
 * rework, and a call whose arguments differ was not the instruction. Reads and the compile check are
 * not rework. The comparison is made on the calls the worker's own event stream shows, never on what
 * the worker's report says it did.</p>
 */
public final class Instructions {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private static final Pattern HEADER = Pattern.compile("(?m)^\\s*INSTRUCTION\\s+(\\d+)\\s*$");
    private static final Pattern TOOL = Pattern.compile("(?m)^\\s*TOOL:\\s*(\\S+)\\s*$");
    private static final Pattern ARGUMENTS = Pattern.compile("(?m)^\\s*ARGUMENTS:\\s*(.*)$");
    /** The lines of the second kind of instruction, new behaviour (Stage 27 (z5)). */
    private static final Pattern BUILD_LINE = Pattern.compile("^\\s*BUILD:\\s*(.*)$");
    private static final Pattern TESTS_LINE = Pattern.compile("^\\s*TESTS:\\s*(.*)$");
    private static final Pattern BULLET = Pattern.compile("^\\s*[-*]\\s+(.*)$");

    /** The argument every tool accepts and none reads as part of its work; ignored when comparing. */
    private static final String META_ARGUMENT = "precedentOverride";

    /** The arguments that say what a call acts on, so a call that names another of one of them is not another go at an instruction. */
    private static final List<String> TARGET_ARGUMENTS = List.of("file_path", "filePath", "path", "symbol", "typeName", "packageName", "command");

    /**
     * 28h D57 (d): the defaults a brain's own editor spells out in its call when the instruction left them out, by tool. Measured
     * 2026-11-17: a Claude developer's Edit through ACP carried {@code "replace_all": false} beside the three arguments it was told.
     * A default the instruction did not name changes nothing it asked for; the same argument with another value does.
     */
    private static final Map<String, Map<String, JsonNode>> EDITOR_DEFAULTS = Map.of(
        "Edit", Map.of("replace_all", com.fasterxml.jackson.databind.node.BooleanNode.FALSE));

    private Instructions() {
    }

    /**
     * One issued instruction.
     *
     * @param number    the number the issuer gave it
     * @param tool      the engine tool it names
     * @param arguments the arguments it gives, or null when the block's arguments were not one JSON
     *                  object: such an instruction has no execution and cannot have run as written
     */
    public record Instruction(int number, String tool, JsonNode arguments) {
    }

    /**
     * One {@code INSTRUCTION n} header and what follows it up to the next header. The one reading of an
     * answer that {@link #parse} and {@link #faults} both start from, so what counts as a block cannot
     * differ between them (D8: a run shows each lead instruction as a block).
     *
     * @param number the number on the header
     * @param tool   the name on its {@code TOOL:} line, or null when the block has none
     * @param body   the text after the header, up to the next header
     */
    private record Block(int number, String tool, String body) {
    }

    private static List<Block> blocks(String answer) {
        List<Block> found = new ArrayList<>();
        if (answer == null || answer.isBlank()) {
            return found;
        }
        List<int[]> headers = new ArrayList<>();      // {start of the header line, end of it, the number}
        Matcher header = HEADER.matcher(answer);
        while (header.find()) {
            headers.add(new int[] {header.start(), header.end(), Integer.parseInt(header.group(1))});
        }
        for (int i = 0; i < headers.size(); i++) {
            int bodyEnd = i + 1 < headers.size() ? headers.get(i + 1)[0] : answer.length();
            String body = answer.substring(headers.get(i)[1], bodyEnd);
            Matcher tool = TOOL.matcher(body);
            found.add(new Block(headers.get(i)[2], tool.find() ? tool.group(1) : null, body));
        }
        return found;
    }

    /**
     * One instruction for NEW BEHAVIOUR (Stage 27 (z5)): no tool and no code, only what is built and each behaviour the tests must prove.
     *
     * @param number the number the issuer gave it
     * @param build  what is built and where, on its {@code BUILD:} line
     * @param tests  one sentence per behaviour a test must prove, from its {@code TESTS:} lines
     */
    public record Build(int number, String build, List<String> tests) {
    }

    /** Every instruction block of {@code answer} that asks for new behaviour ({@code BUILD:}), in order; empty when there are none. */
    public static List<Build> builds(String answer) {
        List<Build> found = new ArrayList<>();
        for (Block block : blocks(answer)) {
            buildOf(block).ifPresent(found::add);
        }
        return found;
    }

    /** The build a block asks for, or empty when it has no {@code BUILD:} line or the line names nothing. */
    private static Optional<Build> buildOf(Block block) {
        String[] lines = block.body().split("\n", -1);
        int at = -1;
        for (int i = 0; i < lines.length && at < 0; i++) {
            if (BUILD_LINE.matcher(lines[i]).matches()) {
                at = i;
            }
        }
        if (at < 0) {
            return Optional.empty();
        }
        StringBuilder what = new StringBuilder(BUILD_LINE.matcher(lines[at]).replaceFirst("$1").trim());
        int next = at + 1;
        while (next < lines.length && !lines[next].isBlank() && !TESTS_LINE.matcher(lines[next]).matches()) {
            what.append(' ').append(lines[next].trim());
            next++;
        }
        List<String> tests = new ArrayList<>();
        boolean inTests = false;
        for (int i = next; i < lines.length; i++) {
            Matcher test = TESTS_LINE.matcher(lines[i]);
            Matcher bullet = BULLET.matcher(lines[i]);
            if (test.matches()) {
                inTests = true;
                if (!test.group(1).isBlank()) {
                    tests.add(test.group(1).trim());
                }
            } else if (inTests && bullet.matches()) {
                tests.add(bullet.group(1).trim());
            } else if (!lines[i].isBlank()) {
                inTests = false;
            }
        }
        String text = what.toString().replaceAll("\\s+", " ").trim();
        return text.isEmpty() ? Optional.empty() : Optional.of(new Build(block.number(), text, List.copyOf(tests)));
    }

    /** Every instruction block in {@code answer}, in order; empty when there are none. */
    public static List<Instruction> parse(String answer) {
        List<Instruction> found = new ArrayList<>();
        for (Block block : blocks(answer)) {
            if (block.tool() != null) {
                found.add(new Instruction(block.number(), block.tool(), argumentsOf(block.body())));
            }
        }
        return found;
    }

    /**
     * What is wrong with the instruction blocks of {@code answer}, in words, one entry per block that
     * is not a whole instruction: a header with no {@code TOOL:} line (which {@link #parse} leaves out,
     * so it would otherwise vanish without a trace) and a block whose {@code ARGUMENTS:} are missing or
     * not one JSON object (which {@link #parse} keeps with null arguments). Empty when every block is
     * whole, and when there are none.
     */
    public static List<String> faults(String answer) {
        List<String> faults = new ArrayList<>();
        for (Block block : blocks(answer)) {
            Optional<Build> build = buildOf(block);
            if (block.tool() != null && build.isPresent()) {
                faults.add("instruction " + block.number() + ": names both a TOOL: and a BUILD:, which are two kinds of instruction and need two blocks");
            } else if (build.isPresent()) {
                if (build.get().tests().isEmpty()) {
                    faults.add("instruction " + block.number() + ": BUILD: without a TESTS: line, so nothing says what the tests must prove");
                }
            } else if (block.tool() == null) {
                faults.add("instruction " + block.number() + ": no TOOL: line, so it names nothing to run and is not counted as issued");
            } else if (argumentsOf(block.body()) == null) {
                faults.add("instruction " + block.number() + ": ARGUMENTS missing or not one JSON object, so it has no execution");
            }
        }
        return faults;
    }

    /** Rows for a record or an answer. */
    public static List<Map<String, Object>> rows(List<Instruction> instructions) {
        List<Map<String, Object>> rows = new ArrayList<>();
        for (Instruction i : instructions) {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("number", i.number());
            row.put("tool", i.tool());
            row.put("arguments", i.arguments());
            rows.add(row);
        }
        return rows;
    }

    /**
     * The instructions {@link #rows} wrote, read back: the other half of the one serialized shape, so
     * the type that writes it is the type that reads it. An instruction whose arguments are not an
     * object comes back with null arguments, as {@link #parse} gives it.
     */
    public static List<Instruction> fromRows(JsonNode rows) {
        List<Instruction> read = new ArrayList<>();
        for (JsonNode node : rows) {
            JsonNode args = node.path("arguments");
            read.add(new Instruction(node.path("number").asInt(), node.path("tool").asText(""),
                args.isObject() ? args : null));
        }
        return read;
    }

    private static JsonNode argumentsOf(String body) {
        Matcher args = ARGUMENTS.matcher(body);
        if (!args.find()) {
            return null;
        }
        // The JSON object may continue over following lines; take everything after the label.
        String json = body.substring(args.start(1)).trim();
        try {
            JsonNode parsed = MAPPER.readTree(json);
            return parsed != null && parsed.isObject() ? parsed : null;
        } catch (Exception e) {
            // Trailing prose after the object is common in a model's answer; read the first object only.
            int close = matchingBrace(json);
            if (close > 0) {
                try {
                    JsonNode parsed = MAPPER.readTree(json.substring(0, close + 1));
                    return parsed != null && parsed.isObject() ? parsed : null;
                } catch (Exception ignored) {
                    return null;
                }
            }
            return null;
        }
    }

    /** The index of the brace closing the object that opens at index 0, honouring strings; -1 if none. */
    private static int matchingBrace(String json) {
        if (json.isEmpty() || json.charAt(0) != '{') {
            return -1;
        }
        int depth = 0;
        boolean inString = false;
        for (int i = 0; i < json.length(); i++) {
            char c = json.charAt(i);
            if (inString) {
                if (c == '\\') {
                    i++;
                } else if (c == '"') {
                    inString = false;
                }
            } else if (c == '"') {
                inString = true;
            } else if (c == '{') {
                depth++;
            } else if (c == '}') {
                depth--;
                if (depth == 0) {
                    return i;
                }
            }
        }
        return -1;
    }

    /**
     * How a worker's calls line up with the instructions it was given.
     *
     * @param issued               how many instructions were given
     * @param ranAsWritten         how many ran as written
     * @param notAsWritten         for each that did not, its number and why, in words
     * @param extraCodeChangingCalls code-changing calls the worker made that no instruction asked for
     */
    public record Score(int issued, int ranAsWritten, List<String> notAsWritten, List<String> extraCodeChangingCalls) {
    }

    /**
     * Compares {@code issued} with the calls {@code made}.
     *
     * @param jawataChanges whether the engine flags a bare tool name as code-changing, the same
     *                      question {@link RunEffects#classify} asks
     */
    public static Score score(List<Instruction> issued, List<ToolCall> made,
            Function<String, Optional<Boolean>> jawataChanges) {
        List<String> notAsWritten = new ArrayList<>();
        int ran = 0;
        boolean[] accounted = new boolean[made.size()];
        // 28h z117: each instruction is scored on the calls made for it. First every call written out by an instruction is claimed by
        // one, so two instructions of one tool, or two that read the same, do not count each other's calls.
        boolean[] written = new boolean[made.size()];
        List<Integer> own = new ArrayList<>();
        for (Instruction instruction : issued) {
            Integer claimed = null;
            for (int i = 0; i < made.size(); i++) {
                if (asWritten(instruction, made.get(i))) {
                    written[i] = true;
                    if (claimed == null && !accounted[i]) {
                        claimed = i;
                        accounted[i] = true;
                    }
                }
            }
            own.add(claimed);
        }
        boolean[] claimed = accounted.clone();
        for (int k = 0; k < issued.size(); k++) {
            Instruction instruction = issued.get(k);
            if (instruction.arguments() == null) {
                notAsWritten.add("instruction " + instruction.number() + ": its ARGUMENTS were not one JSON object, so it has no execution");
                continue;
            }
            // Its further calls: one written like it that no instruction claimed, and one nobody wrote that acts on what it acts on.
            int further = 0;
            boolean toolCalled = false;
            for (int i = 0; i < made.size(); i++) {
                if (!instruction.tool().equals(made.get(i).bareName())) {
                    continue;
                }
                toolCalled = true;
                boolean again = asWritten(instruction, made.get(i)) ? !claimed[i] : !written[i] && actsOnTheSame(instruction, made.get(i).arguments());
                if (again) {
                    further++;
                    accounted[i] = true;
                }
            }
            if (!toolCalled) {
                notAsWritten.add("instruction " + instruction.number() + ": the worker never called " + instruction.tool());
            } else if (own.get(k) == null) {
                notAsWritten.add("instruction " + instruction.number() + ": " + instruction.tool()
                    + " was called, but not with the arguments written");
            } else if (further > 0) {
                notAsWritten.add("instruction " + instruction.number() + ": " + instruction.tool()
                    + " was called " + (further + 1) + " times, which is rework");
            } else {
                ran++;
            }
        }
        List<String> extra = new ArrayList<>();
        for (int i = 0; i < made.size(); i++) {
            if (accounted[i]) {
                continue;
            }
            if (made.get(i).changesCode(jawataChanges)) {
                extra.add(made.get(i).name());
            }
        }
        return new Score(issued.size(), ran, List.copyOf(notAsWritten), List.copyOf(extra));
    }

    /** Whether this call is the instruction's tool carrying what it wrote. */
    private static boolean asWritten(Instruction instruction, ToolCall call) {
        return instruction.arguments() != null && instruction.tool().equals(call.bareName())
            && (equalArguments(instruction.arguments(), withoutGuardBracket(instruction, withoutSpelledOutDefaults(instruction, call.arguments())))
                || sameEditAsItsDiff(instruction, call.arguments())
                || sameEditWithOtherContext(instruction, call.arguments()));
    }

    /**
     * 28h z24: whether an {@code Edit} call and the instruction change the file the same way with different surrounding text: one of the two
     * old texts holds the other exactly once, and making the narrower replacement inside the wider text gives the wider edit's new text.
     */
    private static boolean sameEditWithOtherContext(Instruction instruction, JsonNode made) {
        if (!"Edit".equals(instruction.tool()) || made == null || !made.hasNonNull("old_string") || !made.hasNonNull("new_string")) {
            return false;
        }
        JsonNode written = instruction.arguments();
        if (written.path("replace_all").asBoolean(false) || made.path("replace_all").asBoolean(false)
                || !written.path("file_path").asText("").equals(made.path("file_path").asText())) {
            return false;
        }
        String writtenOld = written.path("old_string").asText("");
        String writtenNew = written.path("new_string").asText("");
        String madeOld = made.path("old_string").asText("");
        String madeNew = made.path("new_string").asText("");
        return replacedWithin(writtenOld, writtenNew, madeOld, madeNew) || replacedWithin(madeOld, madeNew, writtenOld, writtenNew);
    }

    /** Whether the narrower replacement, made once inside the wider old text, gives the wider new text. */
    private static boolean replacedWithin(String widerOld, String widerNew, String narrowOld, String narrowNew) {
        int at = narrowOld.isEmpty() ? -1 : widerOld.indexOf(narrowOld);
        return at >= 0 && widerOld.indexOf(narrowOld, at + 1) < 0
            && widerNew.equals(widerOld.substring(0, at) + narrowNew + widerOld.substring(at + narrowOld.length()));
    }

    /**
     * 28h z117: whether a call of the instruction's tool that no instruction wrote is another go at this one: it names no other target than
     * the instruction names, and an edit also touches the text the instruction replaced or put there.
     */
    private static boolean actsOnTheSame(Instruction instruction, JsonNode made) {
        if (made == null) {
            return true;
        }
        for (String target : TARGET_ARGUMENTS) {
            JsonNode named = instruction.arguments().get(target);
            if (named != null && made.has(target) && !named.equals(made.get(target))) {
                return false;
            }
        }
        if (!"Edit".equals(instruction.tool())) {
            return true;
        }
        String touched = made.has("old_string") ? made.path("old_string").asText()
            : made.has("oldText") ? changedLines(made.path("oldText").asText(), made.path("newText").asText()) : "";
        if (touched.isEmpty()) {
            return true;
        }
        return Stream.of("old_string", "new_string").map(name -> instruction.arguments().path(name).asText(""))
            .anyMatch(text -> !text.isEmpty() && (text.contains(touched) || touched.contains(text)));
    }

    /** The whole lines of {@code before} that an edit known only by its two texts replaced. */
    private static String changedLines(String before, String after) {
        int start = 0;
        while (start < before.length() && start < after.length() && before.charAt(start) == after.charAt(start)) {
            start++;
        }
        int end = 0;
        while (end < before.length() - start && end < after.length() - start
                && before.charAt(before.length() - 1 - end) == after.charAt(after.length() - 1 - end)) {
            end++;
        }
        int from = start == 0 ? 0 : before.lastIndexOf('\n', start - 1) + 1;
        int to = before.indexOf('\n', Math.max(from, before.length() - end));
        return before.substring(from, to < 0 ? before.length() : to);
    }

    /**
     * 28h D57 (d): whether an editor's call known only by its diff (as Cursor's agent reports an edit over ACP: the file, its text before
     * and after) made exactly the one replacement an {@code Edit} instruction wrote: the same file, the old text found once before, and the
     * text after is the text before with that one occurrence replaced.
     */
    private static boolean sameEditAsItsDiff(Instruction instruction, JsonNode made) {
        if (!"Edit".equals(instruction.tool()) || made == null || !made.hasNonNull("oldText") || !made.hasNonNull("newText")) {
            return false;
        }
        JsonNode written = instruction.arguments();
        String oldString = written.path("old_string").asText(null);
        String newString = written.path("new_string").asText(null);
        if (oldString == null || oldString.isEmpty() || newString == null || written.path("replace_all").asBoolean(false)
                || !written.path("file_path").asText("").equals(made.path("file_path").asText())) {
            return false;
        }
        String before = made.get("oldText").asText();
        int at = before.indexOf(oldString);
        if (at < 0 || before.indexOf(oldString, at + 1) >= 0) {
            return false;
        }
        return made.get("newText").asText().equals(before.substring(0, at) + newString + before.substring(at + oldString.length()));
    }

    /**
     * 28h Stage 27 (z24): a shell instruction's command, when the worker ran it through the guard's bracket
     * ({@code jawata-hook-guard bracket --cd D --out F -- <command>}, which the guard requires of a long run), is the command after the
     * {@code --}: the bracket keeps the run's output and says where it ran, and is no difference in what was run.
     */
    private static JsonNode withoutGuardBracket(Instruction instruction, JsonNode made) {
        if (!"Bash".equals(instruction.tool()) || made == null || !made.path("command").isTextual()) {
            return made;
        }
        String command = made.get("command").asText();
        int bracket = command.indexOf("jawata-hook-guard bracket ");
        int separator = bracket < 0 ? -1 : command.indexOf(" -- ", bracket);
        if (separator < 0) {
            return made;
        }
        return ((ObjectNode) made).deepCopy().put("command", command.substring(separator + 4).strip());
    }

    /** The call's arguments less every default of {@link #EDITOR_DEFAULTS} it spells out that the instruction did not name. */
    private static JsonNode withoutSpelledOutDefaults(Instruction instruction, JsonNode made) {
        Map<String, JsonNode> defaults = EDITOR_DEFAULTS.getOrDefault(instruction.tool(), Map.of());
        if (defaults.isEmpty() || made == null || !made.isObject()) {
            return made;
        }
        ObjectNode copy = ((ObjectNode) made).deepCopy();
        defaults.forEach((name, value) -> {
            if (!instruction.arguments().has(name) && value.equals(copy.get(name))) {
                copy.remove(name);
            }
        });
        return copy;
    }

    private static boolean equalArguments(JsonNode written, JsonNode made) {
        return stripped(written).equals(stripped(made));
    }

    private static JsonNode stripped(JsonNode arguments) {
        if (arguments == null || !arguments.isObject()) {
            return MAPPER.createObjectNode();
        }
        ObjectNode copy = ((ObjectNode) arguments).deepCopy();
        copy.remove(META_ARGUMENT);
        return copy;
    }
}
