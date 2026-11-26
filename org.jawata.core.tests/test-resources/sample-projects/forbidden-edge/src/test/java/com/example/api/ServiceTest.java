package com.example.api;

import com.example.internal.Helper;

/** A test source that crosses the api to internal boundary, which a test may. */
public class ServiceTest {

    public int helperValue() {
        return new Helper().value();
    }
}
