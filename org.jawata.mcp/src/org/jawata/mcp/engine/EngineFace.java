package org.jawata.mcp.engine;

/**
 * A way of reaching the engine that is not the MCP listener (Sprint 28k, E19; contract §4.3 and §7): the slot the 28h agent plugs
 * into. An OSGi service of this type is attached once the engine has started and detached before it stops, and is handed an
 * {@link EngineApi} and nothing else.
 */
public interface EngineFace {

    /** What the face is called in the engine's log. */
    String name();

    /** The engine is up; call tools through {@code api}. A face that throws here is skipped and the others still attach. */
    void attach(EngineApi api);

    /** The engine is stopping, or this face was withdrawn; let go of everything {@link #attach} handed over. */
    void detach();
}
