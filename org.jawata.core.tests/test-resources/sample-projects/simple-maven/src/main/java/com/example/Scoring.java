package com.example;

/** Row 48 (Sprint 28d-rescue): a static function worth turning into a command. */
public class Scoring {

    public static int score(int base, int bonus) {
        int weighted = base * 3;
        int total = weighted + bonus;
        return total;
    }

    public int instanceScore(int base) {
        return base + 1;
    }
}
