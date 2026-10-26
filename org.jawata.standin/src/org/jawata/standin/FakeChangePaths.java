package org.jawata.standin;

import java.nio.file.Path;
import java.util.Optional;

import org.eclipse.core.resources.IFile;
import org.eclipse.core.resources.ResourcesPlugin;
import org.eclipse.core.runtime.IPath;
import org.jawata.spi.lang.ChangePaths;

/**
 * How the stand-in language names the files a change touched (Sprint 28k, Stage 8; contract §18 ring 1): a change that carries a plain
 * path names a file in the platform's workspace when the path lies inside one of its projects, and no file otherwise. The lifecycle asks
 * the language for this because only the language knows what its changes carry as their modified element.
 */
public final class FakeChangePaths implements ChangePaths {

    @Override
    public String display(Path absolute) {
        return absolute.toString();
    }

    @Override
    public Optional<IFile> fileOf(Object modifiedElement) {
        if (modifiedElement instanceof IFile file) {
            return Optional.of(file);
        }
        if (modifiedElement instanceof Path path) {
            return Optional.ofNullable(ResourcesPlugin.getWorkspace().getRoot().getFileForLocation(IPath.fromOSString(path.toString())));
        }
        return Optional.empty();
    }
}
