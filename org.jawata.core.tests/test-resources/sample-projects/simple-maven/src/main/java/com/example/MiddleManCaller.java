package com.example;

/** Reaches the department through the middle man, which is what row 36 undoes. */
public class MiddleManCaller {

    public String who(MiddleManPerson person) {
        return person.manager();
    }

    public int howMany(MiddleManPerson person) {
        return person.headcount();
    }
}
