package org.jawata.mcp.java;

import java.nio.file.Path;
import java.util.Optional;

import org.eclipse.core.resources.IFile;
import org.eclipse.jdt.core.ICompilationUnit;
import org.jawata.core.IJdtService;
import org.jawata.spi.lang.ChangePaths;

/**
 * How Java names the files a change touches: through the loaded model's own path formatting (relative or absolute as the
 * headless host is configured, the canonical spelling retried, {@code /} as separator), and a compilation unit as its file.
 */
public final class JavaChangePaths implements ChangePaths {

    private final IJdtService service;

    public JavaChangePaths(IJdtService service) {
        this.service = service;
    }

    @Override
    public String display(Path absolute) {
        return service == null ? absolute.toString() : service.getPathUtils().formatPath(absolute);   // no model: as before E7
    }

    @Override
    public Optional<IFile> fileOf(Object modifiedElement) {
        if (modifiedElement instanceof ICompilationUnit cu && cu.getResource() instanceof IFile file) {
            return Optional.of(file);
        }
        return Optional.empty();
    }
}
