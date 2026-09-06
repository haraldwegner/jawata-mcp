package com.example;

/**
 * TWO top-level types in one file — the shape none of DeleteAtom's three callers had, which is
 * why nothing caught that deleting the compilation unit takes both.
 *
 * <p>{@link PairedInOneFile} is a subclass of {@link TierBase} that carries no distinction, so
 * {@code inline kind=subclass} would otherwise fold it in and delete this file — taking
 * {@code PairedSurvivor} below with it, silently, because nothing outside references it.</p>
 */
public class PairedInOneFile extends TierBase {

    public PairedInOneFile(String name) {
        super(name);
    }

    public int paired() {
        return 1;
    }
}

/**
 * The bystander. It has no relationship to the class above beyond sharing a file, which is
 * exactly the point: a deletion the caller asked for takes it, and it is what nobody named.
 */
class PairedSurvivor {

    int survives() {
        return 2;
    }
}
