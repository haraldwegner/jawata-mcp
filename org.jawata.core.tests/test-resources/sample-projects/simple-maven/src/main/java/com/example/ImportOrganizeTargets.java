package com.example;

import static java.lang.Math.PI;

/**
 * Sprint 25 (spec D1a item 3) fixture for the organize_imports PRUNE capability
 * the old tool lacked: the static import of {@code PI} is never used, so the
 * JDT engine prunes it. (The add-missing-import half of the D1a-3 measure is a
 * separate matter — see parity/organize-imports/DIVERGENCES.md: it NPEs in
 * JDT's headless import rewrite on a null preference, filed as a v2.14.x
 * follow-up; this fixture deliberately requires no added import.)
 */
public class ImportOrganizeTargets {

    public String describe() {
        return "no external types, one unused static import to prune";
    }
}
