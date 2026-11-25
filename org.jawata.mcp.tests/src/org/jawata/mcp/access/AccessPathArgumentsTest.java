package org.jawata.mcp.access;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

import org.jawata.mcp.fixtures.InertShippedTools;
import org.jawata.mcp.tools.Tool;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

/**
 * 28h z70: the access policy sees only the arguments {@link ToolPaths} takes for places. A tool published a {@code folder} that was not among
 * them, so a search read what a read refused. This walks the published schema of every shipped tool, at any depth, and asks {@link ToolPaths}
 * itself whether a value given there is put to the policy; an argument that is named like a place and is not is a failure, unless it is listed
 * here with why it names none.
 */
class AccessPathArgumentsTest {

    /** The words that make an argument's name read as a place. */
    private static final Set<String> PLACE_WORDS = Set.of("path", "paths", "file", "files", "folder", "folders", "dir", "directory", "root", "area",
        "classpath");

    /** Arguments named like a place that name none, each with why. */
    private static final Map<String, String> NAMES_NO_PLACE = Map.of(
        "validate_syntax.fileName", "the name shown for inline source; nothing is opened",
        "find_quality_issue.excludePaths", "substrings that drop findings; nothing is opened");

    @Test
    @DisplayName("every published argument that is named like a place is put to the access policy")
    void everyPlaceArgumentIsChecked() {
        TreeSet<String> unchecked = new TreeSet<>();
        for (Tool tool : InertShippedTools.tools()) {
            walk(tool.getName(), List.of(), tool.getInputSchema(), unchecked);
        }
        unchecked.removeAll(NAMES_NO_PLACE.keySet());

        assertEquals(new TreeSet<String>(), unchecked, "arguments that name a place and are not put to the access policy");
    }

    private static void walk(String tool, List<String> trail, Map<?, ?> schema, Set<String> unchecked) {
        if (!(schema.get("properties") instanceof Map<?, ?> properties)) {
            return;
        }
        for (Map.Entry<?, ?> property : properties.entrySet()) {
            String name = String.valueOf(property.getKey());
            if (!(property.getValue() instanceof Map<?, ?> declared)) {
                continue;
            }
            List<String> here = new ArrayList<>(trail);
            here.add(name);
            Object type = declared.get("type");
            boolean text = "string".equals(type) || type == null;
            boolean list = "array".equals(type) && declared.get("items") instanceof Map<?, ?> items && "string".equals(items.get("type"));
            if ((text || list) && namesAPlace(name) && !isChecked(here, list)) {
                unchecked.add(tool + "." + String.join(".", here));
            }
            if ("object".equals(type)) {
                walk(tool, here, declared, unchecked);
            }
        }
    }

    private static boolean namesAPlace(String name) {
        for (String word : name.split("(?<=[a-z0-9])(?=[A-Z])|_")) {
            if (PLACE_WORDS.contains(word.toLowerCase(java.util.Locale.ROOT))) {
                return true;
            }
        }
        return false;
    }

    /** Whether a value given at this argument reaches the policy, asked of {@link ToolPaths} with a call shaped like the schema. */
    private static boolean isChecked(List<String> trail, boolean list) {
        ObjectMapper json = new ObjectMapper();
        ObjectNode arguments = json.createObjectNode();
        ObjectNode at = arguments;
        for (String step : trail.subList(0, trail.size() - 1)) {
            at = at.putObject(step);
        }
        String leaf = trail.get(trail.size() - 1);
        if (list) {
            at.putArray(leaf).add("/somewhere/probe");
        } else {
            at.put(leaf, "/somewhere/probe");
        }
        return ToolPaths.of(arguments, List.of(), null).places().contains(Path.of("/somewhere/probe"));
    }

    @Test
    @DisplayName("the seats folder a review sweep reads is a place, and one the host's own seats folder may allow")
    void theSeatsFolderArgumentIsChecked() {
        ObjectNode arguments = new ObjectMapper().createObjectNode().put("kind", "review_sweep").put("seats", "/somewhere/seats");

        ToolPaths.Named named = ToolPaths.of(arguments, List.of(), null);

        assertEquals(List.of(Path.of("/somewhere/seats")), named.places());
        assertEquals(Set.of(Path.of("/somewhere/seats")), named.seatFiles());
    }

    @Test
    @DisplayName("a link followed by .. is judged where the operating system opens it, beside the link's target and not beside the link")
    void aLinkFollowedByDotDotIsJudgedBesideItsTarget(@org.junit.jupiter.api.io.TempDir Path temp) throws Exception {
        org.junit.jupiter.api.Assumptions.assumeFalse(System.getProperty("os.name").startsWith("Windows"),
            "Windows resolves .. before it follows a link");
        Path root = temp.toRealPath();
        Path inside = java.nio.file.Files.createDirectories(root.resolve("inside"));
        Path target = java.nio.file.Files.createDirectories(root.resolve("outside/target"));
        java.nio.file.Files.createSymbolicLink(inside.resolve("lnk"), target);

        assertEquals(root.resolve("outside/x"), PlaceNames.normalise(inside.resolve("lnk/../x")));
    }

    @Test
    @DisplayName("a classpath is a list of places, and each of them is put to the policy")
    void eachPlaceOfAClasspathIsChecked() {
        ObjectNode arguments = new ObjectMapper().createObjectNode()
            .put("classpath", "/somewhere/a.jar" + java.io.File.pathSeparator + "/elsewhere/b.jar");

        assertEquals(List.of(Path.of("/somewhere/a.jar"), Path.of("/elsewhere/b.jar")), ToolPaths.of(arguments, List.of(), null).places());
    }
}
