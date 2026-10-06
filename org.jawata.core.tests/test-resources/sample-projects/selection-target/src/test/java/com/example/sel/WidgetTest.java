package com.example.sel;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** The ONLY test that touches Widget — so the only one a Widget edit selects. */
public class WidgetTest {

    @Test
    void bumpsByOne() {
        assertEquals(5, new Widget().bump(4));
    }
}
