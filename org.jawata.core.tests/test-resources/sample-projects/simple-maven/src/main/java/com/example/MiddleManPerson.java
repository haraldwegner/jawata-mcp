package com.example;

/**
 * Two pure forwarders and one method that transforms — the third must survive, because
 * deleting it would change what callers get.
 */
public class MiddleManPerson {

    private final Department department = new Department("Ada");

    public String manager() {
        return department.manager();
    }

    public int headcount() {
        return department.headcount();
    }

    public String shoutedManager() {
        return department.manager().toUpperCase();
    }
}
