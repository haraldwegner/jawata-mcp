package org.jawata.boot;

import org.eclipse.core.runtime.adaptor.EclipseStarter;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

/**
 * Sprint 22d spike — the boot glue: programmatic Equinox startup over a
 * {@code bundles/} directory, replacing the Eclipse launcher + the
 * p2-generated {@code config.ini} (the jdt.ls production pattern).
 *
 * <p>What this class owns (the "boot glue we own forever" of the spec's
 * adopt-decision): locating the bundle set, the start-level ordering that a
 * p2 product bakes into config.ini, the instance (workspace) location, and
 * delegating application launch to Equinox ({@code eclipse.application}).</p>
 *
 * <p>Usage mirrors the product: {@code java -jar org.jawata.boot.jar
 * -data <workspace> [-port N -token T ...]} — app args pass through to
 * {@code JawataApplication} untouched. Session isolation (the launcher's
 * UUID subdir) is deliberately NOT replicated in the spike; dossier note.</p>
 */
public final class JawataBoot {

    /** Start-level overrides — everything else installs at the default level 4. */
    private static final Map<String, String> START_MARKERS = Map.of(
        "org.apache.felix.scr", "@1:start",
        "org.eclipse.equinox.common", "@2:start",
        "org.eclipse.equinox.app", "@2:start",
        "org.eclipse.equinox.registry", "@2:start",
        "org.eclipse.equinox.preferences", "@2:start",
        "org.eclipse.core.runtime", "@start"
    );

    public static void main(String[] args) throws Exception {
        Path home = Path.of(System.getProperty("jawata.boot.home",
            Path.of(JawataBoot.class.getProtectionDomain().getCodeSource()
                .getLocation().toURI()).getParent().toString()));
        Path bundlesDir = home.resolve("bundles");
        if (!Files.isDirectory(bundlesDir)) {
            System.err.println("FATAL: no bundles/ directory beside the boot jar: " + bundlesDir);
            System.exit(2);
        }

        // Publish the stable workspace root exactly like JawataLauncher does
        // (the app reads it for workspace.json / store recovery).
        for (int i = 0; i < args.length - 1; i++) {
            if ("-data".equals(args[i]) && System.getProperty("jawata.workspace.root") == null) {
                System.setProperty("jawata.workspace.root",
                    Path.of(args[i + 1]).toAbsolutePath().toString());
            }
        }

        Map<String, String> props = new HashMap<>();
        props.put("osgi.bundles", osgiBundlesList(bundlesDir));
        props.put("osgi.bundles.defaultStartLevel", "4");
        props.put("eclipse.application", "org.jawata.mcp.application");
        props.put("osgi.configuration.area",
            Files.createTempDirectory("jawata-boot-config").toString());
        props.put("osgi.install.area", home.toString());
        // jdt.ls sets this: legacy code paths expect boot delegation for JDK internals.
        props.put("osgi.compatibility.bootdelegation", "true");

        EclipseStarter.setInitialProperties(props);
        // run() = startup + resolve + start app (eclipse.application), blocks until exit.
        Object exit = EclipseStarter.run(args, null);
        EclipseStarter.shutdown();
        System.exit(exit instanceof Integer i ? i : 0);
    }

    /** Build the config.ini-equivalent bundle list from the bundles/ directory. */
    private static String osgiBundlesList(Path bundlesDir) throws Exception {
        List<String> entries = new ArrayList<>();
        try (Stream<Path> jars = Files.list(bundlesDir)) {
            jars.filter(p -> p.getFileName().toString().endsWith(".jar"))
                .sorted()
                .forEach(jar -> {
                    String name = jar.getFileName().toString();
                    String marker = START_MARKERS.entrySet().stream()
                        .filter(e -> name.startsWith(e.getKey() + "-")
                            || name.startsWith(e.getKey() + "_"))
                        .map(Map.Entry::getValue)
                        .findFirst().orElse("");
                    entries.add("reference:file:" + jar.toAbsolutePath() + marker);
                });
        }
        if (entries.isEmpty()) {
            throw new IllegalStateException("bundles/ is empty: " + bundlesDir);
        }
        return String.join(",", entries);
    }

    private JawataBoot() {}
}
