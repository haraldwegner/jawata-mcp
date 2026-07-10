package com.example;

/**
 * Sprint 20 fixture — Interface Segregation detector.
 * {@code FatService}'s 6 methods are used in disjoint halves by two clients
 * ({a,b,c} by IspClientAbc, {d,e,f} by IspClientDef) → flagged. Every method of
 * {@code CohesiveService} is used together by one client → not flagged.
 */
public class IspTargets {
}

interface FatService {
    void a();
    void b();
    void c();
    void d();
    void e();
    void f();
}

interface CohesiveService {
    void x();
    void y();
    void z();
    void w();
    void p();
    void q();
}

class IspClientAbc {
    FatService s;

    void use() {
        s.a();
        s.b();
        s.c();
    }
}

class IspClientDef {
    FatService s;

    void use() {
        s.d();
        s.e();
        s.f();
    }
}

class IspClientAll {
    CohesiveService c;

    void use() {
        c.x();
        c.y();
        c.z();
        c.w();
        c.p();
        c.q();
    }
}
