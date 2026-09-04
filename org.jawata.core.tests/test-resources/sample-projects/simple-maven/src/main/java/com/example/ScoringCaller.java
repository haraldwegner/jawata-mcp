package com.example;

/** Two call sites, so the command rewrite has references to move. */
public class ScoringCaller {

    public int plain() {
        return Scoring.score(10, 2);
    }

    public int shifted(int bonus) {
        return Scoring.score(20, bonus);
    }
}
