package org.jawata.mcp.execution;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.List;
import java.util.Optional;
import java.util.function.Function;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

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
 * <p>It <em>ran as written</em> when the worker made exactly one call to that tool carrying exactly
 * those arguments and no other call to that tool: a second call with other arguments is rework, and
 * a call whose arguments differ was not the instruction. Reads and the compile check are not
 * rework. The comparison is made on the calls the worker's own event stream shows, never on what the
 * worker's report says it did.</p>
 */
public final class Instructions {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private static final Pattern HEADER = Pattern.compile("(?m)^\\s*INSTRUCTION\\s+(\\d+)\\s*$");
    private static final Pattern TOOL = Pattern.compile("(?m)^\\s*TOOL:\\s*(\\S+)\\s*$");
    private static final Pattern ARGUMENTS = Pattern.compile("(?m)^\\s*ARGUMENTS:\\s*(.*)$");

    /** The argument every tool accepts and none reads as part of its work; ignored when comparing. */
    private static final String META_ARGUMENT = "precedentOverride";

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

    /** Every instruction block in {@code answer}, in order; empty when there are none. */
    public static List<Instruction> parse(String answer) {
        List<Instruction> found = new ArrayList<>();
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
            if (tool.find()) {
                found.add(new Instruction(headers.get(i)[2], tool.group(1), argumentsOf(body)));
            }
        }
        return found;
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
        for (Instruction instruction : issued) {
            if (instruction.arguments() == null) {
                notAsWritten.add("instruction " + instruction.number() + ": its ARGUMENTS were not one JSON object, so it has no execution");
                continue;
            }
            List<Integer> sameTool = new ArrayList<>();
            List<Integer> exact = new ArrayList<>();
            for (int i = 0; i < made.size(); i++) {
                if (instruction.tool().equals(made.get(i).bareName())) {
                    sameTool.add(i);
                    if (equalArguments(instruction.arguments(), made.get(i).arguments())) {
                        exact.add(i);
                    }
                }
            }
            if (sameTool.isEmpty()) {
                notAsWritten.add("instruction " + instruction.number() + ": the worker never called " + instruction.tool());
            } else if (exact.isEmpty()) {
                notAsWritten.add("instruction " + instruction.number() + ": " + instruction.tool()
                    + " was called, but not with the arguments written");
            } else if (sameTool.size() > 1) {
                notAsWritten.add("instruction " + instruction.number() + ": " + instruction.tool()
                    + " was called " + sameTool.size() + " times, which is rework");
            } else {
                ran++;
                accounted[exact.get(0)] = true;
            }
            for (int i : sameTool) {
                accounted[i] = true;
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
