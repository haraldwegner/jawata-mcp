package org.jawata.core.project;

import java.util.Set;

public enum BuildSystem {
	MAVEN, GRADLE, BAZEL, ECLIPSE_PDE, ECLIPSE, UNKNOWN;

	/**
	 * The files whose change moves a project's classpath or settings, so what the
	 * compiler remembers of it can no longer be trusted. One list for everyone who
	 * has to decide between an incremental and a full build: the per-call disk sync
	 * and the plan gate's build planning.
	 */
	public static final Set<String> BUILD_CONFIGURATION_FILES = Set.of("pom.xml", ".classpath", ".project",
			"MANIFEST.MF", "build.properties", "build.gradle", "build.gradle.kts", "settings.gradle",
			"settings.gradle.kts");
}