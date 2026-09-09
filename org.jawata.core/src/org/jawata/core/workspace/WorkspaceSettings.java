package org.jawata.core.workspace;

import org.eclipse.core.resources.ResourcesPlugin;
import org.eclipse.core.runtime.Platform;
import org.eclipse.core.runtime.preferences.InstanceScope;
import org.osgi.service.prefs.BackingStoreException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;

/**
 * mcp#78 — THE ECLIPSE SETTINGS THIS PRODUCT'S BEHAVIOUR DEPENDS ON, STATED.
 *
 * <p>jawata runs inside an Eclipse workspace and reads settings it never wrote. Where a
 * setting is unset Eclipse supplies a default, and that default varies by platform, by JDK,
 * and by whatever a previous run left behind — so every one of them is a latent
 * platform-specific defect waiting for the host that differs. mcp#78 is the governing issue
 * over three such defects (#69 compiler compliance, #75 line delimiter, #39 compliance again),
 * each found on whichever platform's default happened to differ, and it asks for one place
 * that states them rather than a fourth patch.</p>
 *
 * <h2>This list IS the deliverable, and it is how the class closes</h2>
 *
 * <p>The population is SETTINGS, not call sites, so there is no reference query that
 * enumerates it — an unset setting has no code to point at, which is exactly why the class
 * was found by defect rather than by reading. mcp#78's own enumeration makes that concrete:
 * a regex over every line-delimiter spelling returns ZERO matches across 1069 files on a
 * COMPLETE scan, and zero is the finding.</p>
 *
 * <p>So the closing proof is this list. A setting on it with no assignment is an open
 * instance; a defect that later names a setting ABSENT from it means the enumeration was
 * incomplete, and the correction goes here rather than into another per-platform patch.</p>
 */
public final class WorkspaceSettings {

    private static final Logger log = LoggerFactory.getLogger(WorkspaceSettings.class);

    private WorkspaceSettings() {
    }

    /** How Eclipse spells the workspace default charset. */
    static final String CHARSET = "org.eclipse.core.resources/encoding";

    /**
     * One setting the product depends on.
     *
     * @param id       how the setting is spelled where Eclipse stores it
     * @param value    what jawata states it to be, or a description of the computed value
     *                 where it is derived rather than fixed
     * @param reason   why the product's behaviour depends on it — never omitted, because a
     *                 setting nobody can justify is one nobody can review
     * @param appliedBy the class that writes it. Not always this one: compliance is applied
     *                 per project and depends on the project's own declared level, so it
     *                 belongs where that is known. It is DECLARED here anyway, or the list
     *                 would be a list of what is convenient rather than of what is depended on
     */
    public record Setting(String id, String value, String reason, String appliedBy) {
    }

    /** Every Eclipse setting this product's behaviour depends on. */
    public static List<Setting> declared() {
        return List.of(
            new Setting(
                CHARSET,
                "UTF-8",
                "jawata reads and writes source as UTF-8 explicitly everywhere, while JDT"
                    + " decodes compilation units through the WORKSPACE default charset. Unset,"
                    + " that is the platform charset — so on a host whose default is not UTF-8"
                    + " the two disagree about the same bytes, and a non-ASCII identifier or"
                    + " string literal is one thing to jawata and another to the compiler."
                    + " FOUND BY APPLYING mcp#78'S OWN METHOD rather than by a defect: a"
                    + " search for any spelling of the encoding setting returns nothing"
                    + " outside two XML declarations in a test.",
                WorkspaceSettings.class.getSimpleName()),
            new Setting(
                Platform.PREF_LINE_SEPARATOR,
                "\n",
                "Stated rather than inherited, which is mcp#78's whole point — but the"
                    + " dependency is NARROWER than mcp#75 assumed. That issue says Eclipse's"
                    + " generator writes using this preference; measured, the two writers"
                    + " tested (generate kind=constructor, extract kind=method) follow the"
                    + " FILE's own delimiter and not this value, and a CRLF file survives both"
                    + " byte-for-byte. So this is set to keep the default from varying by host"
                    + " rather than to fix an observed defect, and"
                    + " GeneratedMemberKeepsTheFilesDelimiterTest is what would notice if a"
                    + " writer ever started reading it.",
                WorkspaceSettings.class.getSimpleName()),
            new Setting(
                "org.eclipse.jdt.core/org.eclipse.jdt.core.compiler.compliance",
                "the running JVM's level, never lowering a workspace already above it",
                "mcp#69: JDT raises compliance when ITS OWN detection registers the default"
                    + " VM. On macOS that detection returns null, our fallback registered the"
                    + " VM without the level, and a `record` then failed to parse — on macOS"
                    + " only, for three releases. Applied per project rather than here,"
                    + " because the value depends on the project's own declared level.",
                "ProjectImporter#raiseWorkspaceLevelToRunningJvm")
        );
    }

    /**
     * State every setting this class owns. Idempotent, and safe to call before a project
     * exists — these are workspace-scoped.
     *
     * <p>Settings whose {@code appliedBy} names another class are NOT written here; this
     * would otherwise apply a value at the wrong time with less information than the real
     * applier has.</p>
     */
    public static void applyOwned() {
        // THE LIST IS THE SOURCE, not a description of code that repeats it. The first
        // version hard-coded the two writes here and kept `declared()` beside them as
        // metadata — two homes for one fact, and the hollow-wiring gate said so: every
        // caller of `declared()` was test code. Driving the writes FROM the list makes it
        // load-bearing in both directions — drop an entry and it stops being applied; add
        // one this class claims and cannot apply, and the default branch says so rather
        // than letting the register quietly overstate what it states.
        for (Setting setting : declared()) {
            if (!WorkspaceSettings.class.getSimpleName().equals(setting.appliedBy())) {
                continue;   // written where its value is known — e.g. compliance, per project
            }
            try {
                switch (setting.id()) {
                    // READ BEFORE WRITE, and it is not a micro-optimisation. This runs on
                    // EVERY workspace init — every resident start, every project load — and
                    // setDefaultCharset on the workspace ROOT is a resource operation: it
                    // fires a workspace-wide change and can invalidate the cached content
                    // description of every file under it. Writing a value that already holds
                    // pays that for nothing. The first version wrote unconditionally.
                    case CHARSET -> {
                        var wsRoot = ResourcesPlugin.getWorkspace().getRoot();
                        if (!setting.value().equals(wsRoot.getDefaultCharset())) {
                            wsRoot.setDefaultCharset(setting.value(), null);
                        }
                    }
                    case Platform.PREF_LINE_SEPARATOR -> {
                        var node = InstanceScope.INSTANCE.getNode(Platform.PI_RUNTIME);
                        if (!setting.value().equals(node.get(Platform.PREF_LINE_SEPARATOR, null))) {
                            node.put(Platform.PREF_LINE_SEPARATOR, setting.value());
                            node.flush();   // only when it changed; flush writes to disk
                        }
                    }
                    default -> log.warn("mcp#78: '{}' names this class as its applier and this"
                        + " class does not apply it — the register overstates what is stated",
                        setting.id());
                }
            } catch (BackingStoreException | org.eclipse.core.runtime.CoreException
                     | RuntimeException e) {
                // Not fatal: an unset setting is the state this exists to improve on, not one
                // the product cannot run in. Logged rather than thrown so a workspace that
                // refuses the write still starts, with the reason visible.
                log.warn("mcp#78: could not state '{}'; it keeps whatever default the platform"
                    + " supplies", setting.id(), e);
            }
        }
    }
}
