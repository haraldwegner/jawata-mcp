package org.jawata.resident;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * Reads {@code workspace.json}, the file the studio writes to say which projects a headless resident loads (Sprint 28k, Stage 8; ledger
 * 28k-C7-5). Only a resident that is started from a launcher knows this file: a host that has its own workspace (an IDE) never reads it,
 * and the engine does not know it exists.
 */
final class WorkspaceJson {

    private static final Logger log = LoggerFactory.getLogger(WorkspaceJson.class);

    private WorkspaceJson() {
    }

    /**
     * Parse {@code workspace.json} into provenance facets: {@code [workspaceId, projectId]} (the manager's workspace {@code name} plus the
     * first project path; either may be null). Static so the tests can exercise it without booting OSGi.
     *
     * @param workspaceJson the file to read
     * @return the name, which is the folder's name when the file has none, and the first project; both null when the file cannot be read
     */
    static String[] readProvenance(Path workspaceJson) {
        try {
            JsonNode root = new ObjectMapper().readTree(Files.readString(workspaceJson));
            String name = root.path("name").asText(null);
            if (name == null || name.isBlank()) {
                Path parent = workspaceJson.getParent();
                name = parent == null ? null : parent.getFileName().toString();
            }
            String project = null;
            JsonNode projects = root.path("projects");
            if (projects.isArray() && projects.size() > 0) {
                project = projects.get(0).asText(null);
            }
            return new String[] { name, project };
        } catch (Exception e) {
            log.warn("Cannot read provenance from {}: {}", workspaceJson, e.getMessage());
            return new String[] { null, null };
        }
    }

    /**
     * All project paths from {@code workspace.json}.
     *
     * @param workspaceJson the file to read
     * @return the projects in file order; empty on any parse problem
     */
    static List<Path> readProjects(Path workspaceJson) {
        try {
            JsonNode root = new ObjectMapper().readTree(Files.readString(workspaceJson));
            List<Path> out = new ArrayList<>();
            JsonNode projects = root.path("projects");
            if (projects.isArray()) {
                for (JsonNode p : projects) {
                    String s = p.asText(null);
                    if (s != null && !s.isBlank()) {
                        out.add(Path.of(s));
                    }
                }
            }
            return out;
        } catch (Exception e) {
            log.warn("Cannot read projects from {}: {}", workspaceJson, e.getMessage());
            return List.of();
        }
    }
}
