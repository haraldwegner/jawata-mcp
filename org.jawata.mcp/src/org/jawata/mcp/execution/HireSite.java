package org.jawata.mcp.execution;

import java.nio.file.Path;
import java.util.Map;

/**
 * Where a hired brain runs and with what environment — and nothing about WHAT it runs.
 *
 * <p>The hire path used to take a {@link org.jawata.core.host.HostCommand} for this, and a command
 * has an argv. The run replaced that argv with the dialect's, so the one a caller set was thrown away
 * unread; a caller could not tell, and neither could a test whose stand-in process started whatever
 * argv[0] said (S16-13). The dialect owns the argv. This type has no place to put one, so an argv
 * set by a caller cannot exist.</p>
 *
 * @param workingDirectory where the brain runs — its blast radius; {@code null} only for a caller
 *     that states none, which the run reports as a tree it could not census
 * @param environment      variables added to (or, with {@code cleanEnvironment}, replacing) the
 *     resident's own
 * @param cleanEnvironment whether the brain starts from an empty environment plus {@code environment}
 */
public record HireSite(Path workingDirectory, Map<String, String> environment,
        boolean cleanEnvironment) {

    /** A site that runs in {@code directory} with the resident's environment as it is. */
    public static HireSite in(Path directory) {
        return new HireSite(directory, Map.of(), false);
    }
}
