package com.example;

/** Lives in the root whose .classpath entry carries the DIRECT test="true"
 *  spelling. Deliberately imports no test framework: only the declaration can
 *  make this root test code. */
public class InventoryIT {
    /** Returns a marker. */
    public String marker() { return "it"; }
}
