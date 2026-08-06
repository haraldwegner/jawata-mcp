package com.example.latin;

/** The single type of the pde-latin1-manifest fixture. It exists so the bundle
 *  is a real project; the fixture's point is its MANIFEST.MF, whose Bundle-Vendor
 *  carries a raw Latin-1 byte ahead of Bundle-SymbolicName. */
public class Vendor {
    /** Returns the vendor name as this fixture spells it. */
    public String name() { return "Mueller Software GmbH"; }
}
