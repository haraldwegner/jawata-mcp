package org.jawata.mcp.runtime.debug;

import com.sun.jdi.ArrayReference;
import com.sun.jdi.Field;
import com.sun.jdi.ObjectReference;
import com.sun.jdi.PrimitiveValue;
import com.sun.jdi.StringReference;
import com.sun.jdi.Value;
import com.sun.jdi.VoidValue;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Sprint 24 (D6) — turning a live JDI value into something an agent can read,
 * <b>with bounds it can see</b>.
 *
 * <p>An object graph in a running program is unbounded: a linked list is a
 * million deep, a byte[] is a gigabyte, a HashMap holds the world. So expansion
 * is bounded on three axes — depth, items, bytes — and <b>every bound that bites
 * is reported</b> ({@code truncated}, {@code atLeast}, {@code moreFields}). A
 * silently truncated object graph reads as a complete one, and then the agent
 * concludes something false about the program's state. Showing less is fine;
 * pretending it was all there is not.</p>
 */
public final class JdiValues {

    /** Deep enough to see structure; shallow enough not to walk a cyclic graph forever. */
    public static final int DEFAULT_DEPTH = 3;
    /** Array elements / collection entries rendered per level. */
    public static final int DEFAULT_MAX_ITEMS = 20;
    /** Strings and byte arrays are the two things that blow a response up. */
    public static final int DEFAULT_MAX_BYTES = 4096;

    private JdiValues() {
    }

    /** A one-line rendering — what a stack listing or a hit summary shows. */
    public static Object summary(Value value) {
        if (value == null) {
            return null;
        }
        if (value instanceof VoidValue) {
            return "void";
        }
        if (value instanceof PrimitiveValue primitive) {
            return primitiveOf(primitive);
        }
        if (value instanceof StringReference string) {
            return clip(string.value(), DEFAULT_MAX_BYTES);
        }
        if (value instanceof ArrayReference array) {
            return array.referenceType().name() + "[length=" + array.length() + "]";
        }
        if (value instanceof ObjectReference object) {
            return object.referenceType().name() + "@" + object.uniqueID();
        }
        return String.valueOf(value);
    }

    /** The unboxed Java value of a JDI primitive — never a guess about width. */
    public static Object primitiveOf(PrimitiveValue primitive) {
        return switch (primitive.type().name()) {
            case "boolean" -> primitive.booleanValue();
            case "byte" -> primitive.byteValue();
            case "char" -> primitive.charValue();
            case "short" -> primitive.shortValue();
            case "int" -> primitive.intValue();
            case "long" -> primitive.longValue();
            case "float" -> primitive.floatValue();
            case "double" -> primitive.doubleValue();
            default -> primitive.toString();
        };
    }

    public static Map<String, Object> expand(Value value) {
        return expand(value, DEFAULT_DEPTH, DEFAULT_MAX_ITEMS, DEFAULT_MAX_BYTES);
    }

    /**
     * Expand a value to a bounded tree. Each node carries its declared type; each
     * bound that bites is stated on the node it truncated.
     */
    public static Map<String, Object> expand(Value value, int depth, int maxItems, int maxBytes) {
        return expand(value, depth, maxItems, maxBytes, new ArrayList<>());
    }

    private static Map<String, Object> expand(Value value, int depth, int maxItems, int maxBytes,
                                              List<Long> seen) {
        Map<String, Object> node = new LinkedHashMap<>();
        if (value == null) {
            node.put("value", null);
            node.put("type", "null");
            return node;
        }
        node.put("type", value.type() == null ? "unknown" : value.type().name());

        if (value instanceof PrimitiveValue primitive) {
            node.put("value", primitiveOf(primitive));
            return node;
        }
        if (value instanceof StringReference string) {
            String text = string.value();
            node.put("value", clip(text, maxBytes));
            if (text.length() > maxBytes) {
                node.put("truncated", true);
                node.put("fullLength", text.length());
            }
            return node;
        }
        if (!(value instanceof ObjectReference object)) {
            node.put("value", String.valueOf(value));
            return node;
        }

        node.put("id", object.uniqueID());

        // A cycle is normal in a real object graph; walking one forever is not.
        if (seen.contains(object.uniqueID())) {
            node.put("cycle", true);
            return node;
        }
        if (depth <= 0) {
            node.put("summary", summary(object));
            node.put("truncated", true);
            node.put("reason", "depth bound reached — expand this id with a deeper depth to see more");
            return node;
        }
        seen.add(object.uniqueID());
        try {
            if (object instanceof ArrayReference array) {
                expandArray(array, node, depth, maxItems, maxBytes, seen);
                return node;
            }
            expandFields(object, node, depth, maxItems, maxBytes, seen);
            return node;
        } finally {
            seen.remove(object.uniqueID());
        }
    }

    private static void expandArray(ArrayReference array, Map<String, Object> node,
                                    int depth, int maxItems, int maxBytes, List<Long> seen) {
        int length = array.length();
        node.put("length", length);
        int shown = Math.min(length, maxItems);
        List<Object> items = new ArrayList<>();
        for (Value element : array.getValues(0, shown)) {
            items.add(expand(element, depth - 1, maxItems, maxBytes, seen));
        }
        node.put("items", items);
        if (shown < length) {
            node.put("truncated", true);
            node.put("shownItems", shown);
            node.put("reason", "item bound reached — " + length + " elements exist, " + shown
                + " shown; raise maxItems to see more");
        }
    }

    private static void expandFields(ObjectReference object, Map<String, Object> node,
                                     int depth, int maxItems, int maxBytes, List<Long> seen) {
        List<Field> fields;
        try {
            fields = object.referenceType().allFields();
        } catch (Exception e) {
            node.put("fieldsUnavailable", e.getMessage());
            return;
        }
        Map<String, Object> values = new LinkedHashMap<>();
        int shown = 0;
        for (Field field : fields) {
            if (field.isStatic()) {
                continue;   // an instance's statics belong to its class, not to it
            }
            if (shown >= maxItems) {
                node.put("truncated", true);
                node.put("moreFields", true);
                node.put("reason", "field bound reached — raise maxItems to see the rest");
                break;
            }
            try {
                values.put(field.name(),
                    expand(object.getValue(field), depth - 1, maxItems, maxBytes, seen));
            } catch (Exception e) {
                values.put(field.name(), Map.of("unreadable", String.valueOf(e.getMessage())));
            }
            shown++;
        }
        node.put("fields", values);
    }

    private static String clip(String text, int maxBytes) {
        if (text == null || text.length() <= maxBytes) {
            return text;
        }
        return text.substring(0, maxBytes) + "…";
    }
}
