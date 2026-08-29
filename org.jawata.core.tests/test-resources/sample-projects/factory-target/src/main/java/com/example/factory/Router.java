package com.example.factory;

/**
 * Sprint 28d Stage 8 fixture — the shape Replace Conditional with Polymorphism acts
 * on: a switch on an ENUM whose arms differ by behaviour.
 *
 * <p>Deliberately built to defeat a "refuse anything mentioning the this keyword"
 * bound, because that is the bound {@code refactor_to_state} takes and it would
 * reject the real before-case. The arms here assign through {@code this} AND read a
 * BARE field, so a rewrite that handles only the qualified form leaves the bare
 * reference pointing at nothing once the body moves into another class.</p>
 *
 * <p>{@code multiplier} is a field; {@code count} is a field; {@code amount} is a
 * PARAMETER that shares nothing with them. A binding-driven rewrite redirects the
 * two fields at the context and leaves the parameter alone — a textual one would
 * corrupt it.</p>
 */
public class Router {

    enum Signal { START, STOP, PAUSE }

    private int count;
    private int multiplier = 2;

    public void handle(Signal signal, int amount) {
        switch (signal) {
            case START -> {
                this.count = amount * multiplier;
            }
            case STOP -> {
                this.count = 0;
            }
            default -> {
                this.count = count + amount;
            }
        }
    }

    public int count() {
        return count;
    }
}
