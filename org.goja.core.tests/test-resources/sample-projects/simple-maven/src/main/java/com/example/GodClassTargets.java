package com.example;

/**
 * Sprint 17 fixture — God Class detector.
 * <ul>
 *   <li>{@code GodClassTarget} — 21 members AND referenced by 8 types: God Class.</li>
 *   <li>{@code LonelyLargeClass} — 21 members but referenced by nobody: large_classes
 *       only, NOT a God Class (fan-in 0).</li>
 *   <li>{@code GUser1..GUser8} — eight distinct types that reference GodClassTarget,
 *       giving it the fan-in that distinguishes it from a merely large class.</li>
 * </ul>
 */
public class GodClassTargets {
}

class GodClassTarget {
    public void m1() {}
    public void m2() {}
    public void m3() {}
    public void m4() {}
    public void m5() {}
    public void m6() {}
    public void m7() {}
    public void m8() {}
    public void m9() {}
    public void m10() {}
    public void m11() {}
    public void m12() {}
    public void m13() {}
    public void m14() {}
    public void m15() {}
    public void m16() {}
    public void m17() {}
    public void m18() {}
    public void m19() {}
    public void m20() {}
    public void m21() {}
}

// Large but central to nobody — large_classes should flag it, god_class must NOT.
class LonelyLargeClass {
    public void a1() {}
    public void a2() {}
    public void a3() {}
    public void a4() {}
    public void a5() {}
    public void a6() {}
    public void a7() {}
    public void a8() {}
    public void a9() {}
    public void a10() {}
    public void a11() {}
    public void a12() {}
    public void a13() {}
    public void a14() {}
    public void a15() {}
    public void a16() {}
    public void a17() {}
    public void a18() {}
    public void a19() {}
    public void a20() {}
    public void a21() {}
}

class GUser1 { GodClassTarget g = new GodClassTarget(); }
class GUser2 { GodClassTarget g = new GodClassTarget(); }
class GUser3 { GodClassTarget g = new GodClassTarget(); }
class GUser4 { GodClassTarget g = new GodClassTarget(); }
class GUser5 { GodClassTarget g = new GodClassTarget(); }
class GUser6 { GodClassTarget g = new GodClassTarget(); }
class GUser7 { GodClassTarget g = new GodClassTarget(); }
class GUser8 { GodClassTarget g = new GodClassTarget(); }
