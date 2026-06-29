package com.example;

/**
 * Sprint 17 fixture — Shotgun Surgery detector.
 * {@code ShotgunTarget} is referenced by 6 distinct types → flagged (a change
 * to it ripples). {@code LonelyShotgun} is referenced by none → not flagged.
 */
public class ShotgunTargets {
}

class ShotgunTarget {
    int value;

    int get() {
        return value;
    }
}

class LonelyShotgun {
    int value;
}

class SUser1 { ShotgunTarget t = new ShotgunTarget(); }
class SUser2 { ShotgunTarget t = new ShotgunTarget(); }
class SUser3 { ShotgunTarget t = new ShotgunTarget(); }
class SUser4 { ShotgunTarget t = new ShotgunTarget(); }
class SUser5 { ShotgunTarget t = new ShotgunTarget(); }
class SUser6 { ShotgunTarget t = new ShotgunTarget(); }
