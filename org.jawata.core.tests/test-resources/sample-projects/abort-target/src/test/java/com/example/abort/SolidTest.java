package com.example.abort;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

public class SolidTest {

    @Test
    void timesIsSix() {
        assertEquals(6, new Solid().times(2, 3));
    }
}
