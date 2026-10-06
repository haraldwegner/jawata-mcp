package com.example.sink;

/**
 * Sprint 28g S5 / D9 row 2 — the DESTINATION, deliberately in a package of its own.
 *
 * <p>{@code com.example} already depends on {@code com.example.sink}, because the owner holds a
 * port and hands work to it. The edge the move would ADD runs the other way — the sink coming to
 * depend on the pipeline that feeds it — which is the inversion the census says those sixteen
 * bodies were re-homed by hand to avoid: <em>"every one of these bodies had to reach it through a
 * port or a listener instead, because `execution` may not name `pipeline`"</em>. A same-package
 * destination would make the layering refusal a statement about nothing.</p>
 */
public class Port {

    private final String id;

    public Port(String id) {
        this.id = id;
    }

    public String id() {
        return id;
    }
}
