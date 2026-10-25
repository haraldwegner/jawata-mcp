package org.jawata.mcp.engine;

/**
 * Raised when a face asks the engine for a tool name it does not publish (Sprint 28k, E19). It lives beside {@link ToolCalls}
 * because it is part of that entry's contract: a face that catches it need not name the registry that raised it.
 */
public class ToolNotFoundException extends Exception {

    private static final long serialVersionUID = 1L;

    /**
     * Builds the error for one unknown tool name.
     *
     * @param message what the caller is told, including a hint toward the right name when there is one
     */
    public ToolNotFoundException(String message) {
        super(message);
    }
}
