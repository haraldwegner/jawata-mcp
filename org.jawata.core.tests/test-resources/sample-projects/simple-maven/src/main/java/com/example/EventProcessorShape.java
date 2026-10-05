package com.example;

/**
 * Sprint 28g S3 / D7a — the shape of strategies_orb's OrbEventProcessor.java, where
 * extract kind=method answers "IllegalStateException: source not specified" for EVERY
 * selection, while extract kind=variable on the same method stages a clean diff and
 * extract kind=method on its sibling SlotManager.java stages cleanly too.
 *
 * The distinguishing features from the defect record: TWO nested INTERFACES declared
 * between the fields and the constructor, and an arrow switch over a nested enum.
 *
 * DO NOT REORDER. A test addresses handleTick's two statements by line, so inserting
 * or removing a line above line 43 moves the selection and the test reads the wrong code.
 */
public class EventProcessorShape {

    private final String name;
    private int counter;

    public enum Mode { BAR, TICK, QUOTE }

    public interface FirstHandler {
        void handleFirst(String symbol);
    }

    public interface SecondHandler {
        void handleSecond(String symbol);
    }

    public EventProcessorShape(String name) {
        this.name = name;
    }

    public void dispatch(Mode m, String symbol) {
        switch (m) {
            case BAR -> handleBar(symbol);
            case TICK -> handleTick(symbol);
            case QUOTE -> handleQuote(symbol);
        }
    }

    private void handleBar(String symbol) {
        counter++;
    }

    private void handleTick(String symbol) {
        counter = counter + 1;
        System.out.println(name + symbol);
    }

    private void handleQuote(String symbol) {
        counter--;
    }
}
