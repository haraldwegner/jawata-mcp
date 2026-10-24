package org.jawata.mcp.java;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.function.Supplier;

import org.eclipse.jdt.core.ICompilationUnit;
import org.eclipse.jdt.core.IMember;
import org.eclipse.jdt.core.ISourceRange;
import org.eclipse.jdt.core.IType;
import org.jawata.core.IJdtService;
import org.jawata.spi.lang.SymbolAnchors;

/**
 * Java's {@link SymbolAnchors}: memory's anchors are fully-qualified Java names ("pkg.Type" or "pkg.Type#member"), resolved and
 * located through the loaded model (contract §4.5, E8). The resolution is {@link SymbolAnchorResolver}'s; E9 moves that class's
 * body here and rewires the knowledge store to ask this port.
 */
public final class JavaSymbolAnchors implements SymbolAnchors {

    private final IJdtService service;
    private final SymbolAnchorResolver resolver;

    JavaSymbolAnchors(IJdtService service) {
        this.service = service;
        this.resolver = new SymbolAnchorResolver(() -> service);
    }

    /**
     * Java's anchors over whatever model is loaded at the moment of the ask, or null while none is — what the knowledge classes
     * hold as their {@code Supplier<SymbolAnchors>} (E9, K10). Each ask gets a fresh instance, so the resolver's token memo spans
     * one load or backfill run and never outlives a project load or removal.
     */
    static Supplier<SymbolAnchors> over(Supplier<IJdtService> model) {
        return () -> {
            IJdtService loaded = model == null ? null : model.get();
            return loaded == null ? null : new JavaSymbolAnchors(loaded);
        };
    }

    @Override
    public Optional<String> anchorFor(String freeText) {
        return resolver.resolve(freeText);
    }

    /**
     * The body of what was {@code ExperienceRetrieval#resolvePointer} (E9, moved whole): one answer to "does this anchor still
     * resolve" that the write gate and the staleness sweep both read.
     */
    @Override
    public Map<String, Object> pointer(String symbolFqn) {
        int hash = symbolFqn.indexOf('#');
        String typeName = hash < 0 ? symbolFqn : symbolFqn.substring(0, hash);
        String member = hash < 0 ? null : symbolFqn.substring(hash + 1).strip();
        Map<String, Object> p = new LinkedHashMap<>();
        p.put("symbol", symbolFqn);
        if (service == null) {
            p.put("resolved", false);
            p.put("note", "no project loaded");
            return p;
        }
        // Sprint 28g S14 D40d — A CLOSED PROJECT IS A FAILURE TO LOOK, NOT A MISSING SYMBOL.
        //
        // A service in hand with NOTHING LOADED answers `findType` null without throwing, which would fall into the branch
        // below and set `stale` — so an unopened or closed workspace would report every anchor in the store as GONE, and the
        // write gate would refuse correct rows while the sweep queued the whole store for re-anchoring. The distinction is
        // made HERE rather than at each caller, because the two callers ask ONE object.
        //
        // AN UNREADABLE PROJECT SET FALLS THROUGH; IT DOES NOT ANSWER HERE. This branch speaks only for the ONE state it was
        // written for — where it cannot tell, the resolution below proceeds and its own error wording survives. And the empty
        // set is a proxy: `projectKeys` defaults to List.of(), so "empty" is also what an implementation that never overrode
        // it answers; UNKNOWN is the safe direction for both, because it never retires a row and never refuses a write.
        java.util.Collection<String> loaded;
        try {
            loaded = service.projectKeys();
        } catch (RuntimeException ex) {
            loaded = null;
        }
        if (loaded != null && loaded.isEmpty()) {
            p.put("resolved", false);
            p.put("note", "no project is loaded in this workspace, so nothing could be"
                + " looked up — this is a failure to look and not a missing symbol");
            return p;
        }
        try {
            IType type = service.findType(typeName);
            if (type == null || !type.exists()) {
                p.put("resolved", false);
                p.put("stale", true);
                p.put("note", "symbol not found in current workspace");
                return p;
            }
            IMember target = type;
            if (member != null && !member.isEmpty()) {
                // THE STRICT FORM, because this answer backs a REFUSAL. The forgiving `memberOn` turns an unreadable type into
                // "no such member", which for the auto-anchoring heuristic is right and for a user-facing refusal would tell
                // the author the member is gone when the lookup merely failed. Anything that stops the lookup completing now
                // propagates to the catch below, where it becomes "could not check".
                IMember found = SymbolAnchorResolver.memberOnOrThrow(type, member);
                if (found == null) {
                    p.put("resolved", false);
                    p.put("stale", true);
                    p.put("note", "the type is here and '" + member + "' is not — "
                        + "the anchored member is gone");
                    locate(service, type, type, p);
                    return p;
                }
                p.put("member", member);
                target = found;
            }
            p.put("resolved", true);
            locate(service, type, target, p);
        } catch (Exception ex) {
            p.put("resolved", false);
            p.put("note", "resolution error: " + ex.getMessage());
        }
        return p;
    }

    /** A Java anchor reads as a qualified name: dotted identifiers, optionally followed by {@code #member}. */
    @Override
    public boolean answersFor(String symbol) {
        return symbol != null && symbol.matches("[A-Za-z_$][\\w$]*(\\.[A-Za-z_$][\\w$]*)*(#[A-Za-z_$][\\w$]*(\\(.*\\))?)?");
    }

	/**
	     * Put {@code file} and the 1-based {@code line} of {@code member} into {@code p}.
	     *
	     * <p>A location is an AID and never the claim, so an unreadable source range leaves the
	     * pointer without a line rather than failing a resolution that has already succeeded —
	     * the resolution answered whether the code is there, which is a question the range
	     * cannot re-open. The name range rather than the source range, because a reader sent to
	     * a method wants the signature and not the first line of its javadoc.</p>
	     */
	    public static void locate(IJdtService service, IType type, IMember member,
	            Map<String, Object> p) {
	        try {
	            if (type.getResource() != null && type.getResource().getLocation() != null) {
	                p.put("file", type.getResource().getLocation().toOSString());
	            }
	            ICompilationUnit cu = type.getCompilationUnit();
	            ISourceRange name = member.getNameRange();
	            if (cu != null && name != null && name.getOffset() >= 0) {
	                p.put("line", service.getLineNumber(cu, name.getOffset()) + 1);
	            }
	        } catch (Exception ignored) {
	            // see the javadoc: no line is a smaller answer, never a wrong one.
	        }
	    }
}
