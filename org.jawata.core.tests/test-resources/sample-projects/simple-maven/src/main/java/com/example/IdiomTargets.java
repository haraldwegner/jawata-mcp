package com.example;

/**
 * Sprint 19 fixture — replace_pattern_with_idiom (anonymous class -> lambda).
 * Worker.make() returns an anonymous Runnable (the `new` keyword is at 0-based
 * line 12, column 15); the idiom rewrites it to a lambda.
 */
public class IdiomTargets {
}

class Worker {
    Runnable make() {
        return new Runnable() {
            @Override
            public void run() {
                System.out.println("work");
            }
        };
    }
}
