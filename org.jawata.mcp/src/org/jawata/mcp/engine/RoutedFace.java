package org.jawata.mcp.engine;

import com.sun.net.httpserver.HttpHandler;

/**
 * A face that is reached on a route of the engine's own HTTP listener, beside {@code /mcp} (Sprint 28h, S5). The listener belongs to the
 * host; the face says only which path it answers on and with what. The listener puts the same key in front of every route, so a face
 * never sees a request that did not carry it.
 */
public interface RoutedFace extends EngineFace {

    /** The path the face answers on, for example {@code /acp}; requests to it and below it reach {@link #handler()}. */
    String path();

    /** Answers the requests that arrive on {@link #path()}. */
    HttpHandler handler();
}
