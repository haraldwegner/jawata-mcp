package org.jawata.mcp.access;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * Which places on the machine an agent's tool call may name (Sprint 28h, S13, D20, ACL1 to ACL7 and ACL9). The whole policy is one
 * file the person owns: a list of places that are allowed and a list that are not, and nothing about places is compiled in.
 *
 * <p><b>What a decision depends on.</b> The file, the variables the file's entries are written with ({@code ${home}},
 * {@code ${temp}}, {@code ${workspace}}), and the path asked about; nothing else (ACL1). A place on the deny list is refused even
 * when it lies inside an allowed one (ACL3). A place on neither list is refused (ACL2). A name is never read for what it looks like:
 * a file called {@code credentials.json} is allowed when the allowed places hold it and refused when the deny list names it (ACL6).</p>
 *
 * <p><b>It fails toward refusing.</b> A file that is missing, unreadable, lacks either list, uses a variable nobody defined or holds
 * an entry that is not an absolute path makes a policy that refuses every place, and the refusal says which of those it was (ACL5).
 * An entry whose variable has no value (a session with no workspace of its own) holds nothing and so allows nothing, which is the
 * narrower reading.</p>
 */
public final class AccessPolicy {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final Pattern VARIABLE = Pattern.compile("\\$\\{([A-Za-z_][A-Za-z0-9_]*)}");

    /** The answer for one place: whether it may be named, and when not, in whose words it was refused. */
    public record Decision(boolean allowed, String reason) {

        static Decision allow() {
            return new Decision(true, "");
        }

        static Decision deny(String reason) {
            return new Decision(false, reason);
        }
    }

    private final List<Path> allow;
    private final List<Path> deny;
    private final String unusable;

    private AccessPolicy(List<Path> allow, List<Path> deny, String unusable) {
        this.allow = List.copyOf(allow);
        this.deny = List.copyOf(deny);
        this.unusable = unusable;
    }

    /** A policy that refuses every place and says {@code reason} when it does. */
    public static AccessPolicy unusable(String reason) {
        return new AccessPolicy(List.of(), List.of(), reason);
    }

    /**
     * Reads the policy file. Never throws and never returns null: whatever is wrong with the file comes back as a policy that
     * refuses everything and names what was wrong.
     *
     * @param file      the one declared policy file
     * @param variables each variable the entries may use, with the places it stands for; an empty list means it stands for none
     */
    public static AccessPolicy read(Path file, Map<String, List<Path>> variables) {
        String text;
        try {
            text = Files.readString(file);
        } catch (java.nio.file.NoSuchFileException e) {
            return unusable("there is no access policy at " + file + ", so no place may be named");
        } catch (IOException e) {
            return unusable("the access policy at " + file + " could not be read (" + e.getMessage() + "), so no place may be named");
        }
        JsonNode root;
        try {
            root = JSON.readTree(text);
        } catch (IOException e) {
            return unusable("the access policy at " + file + " is not valid JSON (" + firstLine(e.getMessage()) + "), so no place may be named");
        }
        if (root == null || !root.isObject()) {
            return unusable("the access policy at " + file + " is not a JSON object, so no place may be named");
        }
        List<Path> allow = new ArrayList<>();
        List<Path> deny = new ArrayList<>();
        for (String list : new String[] {"allow", "deny"}) {
            JsonNode entries = root.get(list);
            if (entries == null || !entries.isArray()) {
                return unusable("the access policy at " + file + " has no \"" + list + "\" list, so no place may be named");
            }
            for (JsonNode entry : entries) {
                if (!entry.isTextual()) {
                    return unusable("the access policy at " + file + " has an entry in \"" + list + "\" that is not text, so no place may be named");
                }
                String problem = expand(entry.asText(), variables, "allow".equals(list) ? allow : deny);
                if (problem != null) {
                    return unusable("the access policy at " + file + ": entry \"" + entry.asText() + "\" in \"" + list + "\" " + problem
                        + ", so no place may be named");
                }
            }
        }
        return new AccessPolicy(allow, deny, null);
    }

    /** Adds the places {@code entry} stands for to {@code into}; returns what is wrong with the entry, or null. */
    private static String expand(String entry, Map<String, List<Path>> variables, List<Path> into) {
        Matcher matcher = VARIABLE.matcher(entry);
        String name = null;
        while (matcher.find()) {
            String found = matcher.group(1);
            if (!variables.containsKey(found)) {
                return "uses ${" + found + "}, which is not defined";
            }
            if (name != null && !name.equals(found)) {
                return "uses more than one variable";
            }
            name = found;
        }
        if (name == null) {
            return addIfAbsolute(entry, into);
        }
        for (Path value : variables.get(name)) {
            String problem = addIfAbsolute(entry.replace("${" + name + "}", value.toString()), into);
            if (problem != null) {
                return problem;
            }
        }
        return null;
    }

    private static String addIfAbsolute(String entry, List<Path> into) {
        Path path;
        try {
            path = Path.of(entry);
        } catch (java.nio.file.InvalidPathException e) {
            return "is not a path on this machine";
        }
        if (!path.isAbsolute()) {
            return "is not an absolute path";
        }
        into.add(PlaceNames.normalise(path));
        return null;
    }

    /** Whether {@code place} may be named. */
    public Decision check(Path place) {
        if (unusable != null) {
            return Decision.deny(unusable);
        }
        Path at = PlaceNames.normalise(place);
        for (Path denied : deny) {
            if (at.startsWith(denied)) {
                return Decision.deny("'" + place + "' is on the access policy's deny list (" + denied + ")");
            }
        }
        for (Path allowed : allow) {
            if (at.startsWith(allowed)) {
                return Decision.allow();
            }
        }
        return Decision.deny("'" + place + "' is outside every place the access policy allows");
    }

    private static String firstLine(String message) {
        if (message == null) {
            return "no detail";
        }
        int end = message.indexOf('\n');
        return end < 0 ? message : message.substring(0, end);
    }
}
