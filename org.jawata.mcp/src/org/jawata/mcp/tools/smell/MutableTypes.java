package org.jawata.mcp.tools.smell;

import org.eclipse.jdt.core.dom.ITypeBinding;
import org.eclipse.jdt.core.dom.Type;

import java.util.Set;

/**
 * WHICH TYPES HOLD STATE SOMEONE ELSE CAN CHANGE.
 *
 * <p>Two Sprint 28d-rescue detectors need the same judgement and would otherwise each
 * carry a list. {@code global_data} asks it of a static final field — does {@code final}
 * actually freeze this, or only the reference. {@code mutable_data} asks it of a
 * method's return — does handing this out hand out the object's insides. One question,
 * so one list; two copies of it would disagree the first time either grew.</p>
 *
 * <p><b>It is a list and not an inference, and that is a limit worth stating.</b> There
 * is no way to ask a Java type whether it is immutable: an application's own immutable
 * value type and a mutable one are indistinguishable without reading them. So this
 * names the JDK shapes that actually turn up in the two positions above, and treats
 * everything else as immutable. That direction is deliberate — a missed finding costs
 * less than a report on every constant and every accessor in a codebase, which is the
 * failure that makes a detector get switched off.</p>
 */
final class MutableTypes {

    private static final Set<String> NAMES = Set.of(
        "Collection", "List", "ArrayList", "LinkedList", "Vector",
        "Set", "HashSet", "LinkedHashSet", "TreeSet", "SortedSet", "NavigableSet",
        "Map", "HashMap", "LinkedHashMap", "TreeMap", "SortedMap", "NavigableMap",
        "Hashtable", "Properties", "ConcurrentHashMap", "ConcurrentMap",
        "Queue", "Deque", "ArrayDeque", "PriorityQueue", "BlockingQueue",
        "StringBuilder", "StringBuffer",
        "Date", "Calendar", "GregorianCalendar",
        "AtomicReference", "AtomicInteger", "AtomicLong", "AtomicBoolean");

    private MutableTypes() {
    }

    /** The simple name if this binding is a known-mutable type, else null. */
    static String nameIfMutable(ITypeBinding binding) {
        if (binding == null) {
            return null;
        }
        if (binding.isArray()) {
            // Always, whatever the element type: every slot stays writable.
            return "array";
        }
        ITypeBinding erasure = binding.getErasure() != null ? binding.getErasure() : binding;
        String simple = erasure.getName();
        return simple != null && NAMES.contains(simple) ? simple : null;
    }

    /**
     * The same question of a DECLARED type, which answers the array case without
     * needing a binding — the one shape that resolves even when bindings do not.
     */
    static String nameIfMutable(Type type) {
        if (type == null) {
            return null;
        }
        if (type.isArrayType()) {
            return "array";
        }
        return nameIfMutable(type.resolveBinding());
    }
}
