package com.example;

/**
 * Sprint 19 fixture — refactor_to_state. TrafficLight.signal() switches on an int
 * state field (switch at 0-based line 23) with type-code cases; reset() is a
 * transition. refactor_to_state introduces a nested TrafficLightState interface +
 * inner state classes and delegates.
 */
public class StateTargets {
}

class TrafficLight {
    static final int RED = 0;
    static final int GREEN = 1;
    static final int YELLOW = 2;

    private int state = RED;

    void reset() {
        state = RED;
    }

    String signal() {
        switch (state) {
            case RED:
                return "stop";
            case GREEN:
                return "go";
            case YELLOW:
                return "slow";
            default:
                return "off";
        }
    }
}
