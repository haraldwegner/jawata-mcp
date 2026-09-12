package com.example;

/** CONTROL THREE, half 2 — one shared collaborator is not enough. */
public class SoloB {

    public String tag(Item item) {
        Doc doc = new Doc();
        return item.label() + doc.title();
    }
}
