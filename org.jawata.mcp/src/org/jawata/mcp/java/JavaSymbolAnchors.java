package org.jawata.mcp.java;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

import org.eclipse.jdt.core.ICompilationUnit;
import org.eclipse.jdt.core.IMember;
import org.eclipse.jdt.core.ISourceRange;
import org.eclipse.jdt.core.IType;
import org.jawata.core.IJdtService;
import org.jawata.mcp.knowledge.SymbolAnchorResolver;
import org.jawata.spi.lang.Location;
import org.jawata.spi.lang.SymbolAnchors;

/**
 * Java's {@link SymbolAnchors}: memory's anchors are fully-qualified Java names ("pkg.Type" or "pkg.Type#member"), resolved and
 * located through the loaded model (contract §4.5, E8). The resolution is {@link SymbolAnchorResolver}'s; E9 moves that class's
 * body here and rewires the knowledge store to ask this port.
 */
public final class JavaSymbolAnchors implements SymbolAnchors {

    private final IJdtService service;
    private final SymbolAnchorResolver resolver;

    public JavaSymbolAnchors(IJdtService service) {
        this.service = service;
        this.resolver = new SymbolAnchorResolver(() -> service);
    }

    @Override
    public Optional<String> anchorFor(String freeText) {
        return resolver.resolve(freeText);
    }

    @Override
    public Optional<Location> locate(String symbol) {
        if (symbol == null || symbol.isBlank()) {
            return Optional.empty();
        }
        int hash = symbol.indexOf('#');
        String typeName = hash < 0 ? symbol.strip() : symbol.substring(0, hash).strip();
        String member = hash < 0 ? "" : symbol.substring(hash + 1).strip();
        try {
            IType type = service.findType(typeName);
            if (type == null || !type.exists()) {
                return Optional.empty();
            }
            IMember target = member.isEmpty() ? type : SymbolAnchorResolver.memberOnOrThrow(type, member);
            if (target == null || !target.exists()) {
                return Optional.empty();
            }
            Map<String, Object> where = new LinkedHashMap<>();
            locate(service, type, target, where);
            Object file = where.get("file");
            if (file == null) {
                return Optional.empty();
            }
            return Optional.of(new Location(file.toString(), where.get("line") instanceof Integer line ? line : -1));
        } catch (Exception unreadable) {
            return Optional.empty();
        }
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
