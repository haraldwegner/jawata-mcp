package com.example.libuser;

import com.example.nested.one.FromFirstJar;

/** Compiles only when the container's NESTED jar types are on the classpath. */
public class UsesNested {
    public String label() {
        return new FromFirstJar().label();
    }
}
