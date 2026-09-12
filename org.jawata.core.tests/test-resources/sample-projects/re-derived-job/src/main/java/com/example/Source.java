package com.example;

/** Scenery: what a parse helper is pointed at. */
public class Source {

    private final String text;

    public Source(String text) {
        this.text = text;
    }

    public String text() {
        return text;
    }
}
