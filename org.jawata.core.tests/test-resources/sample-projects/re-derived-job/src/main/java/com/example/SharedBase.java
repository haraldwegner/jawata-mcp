package com.example;

/**
 * CONTROL FOUR's base — the pair below SHARE this, and are reported anyway.
 *
 * <p>Deliberately declares nothing the subclasses declare. That is the whole point: sharing
 * a parent is not the same as having a signature dictated by one, and this class exists so
 * the difference can be measured instead of argued.</p>
 */
public abstract class SharedBase {

    public String describe() {
        return "shared";
    }
}
