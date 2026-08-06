package com.example;

/** A project with NO build file of any kind — detection must say UNKNOWN and the
 *  project must still mount what it has, rather than loading empty and looking healthy. */
public class Widget {

    /** Returns this widget's label. */
    public String label() {
        return "widget";
    }
}
