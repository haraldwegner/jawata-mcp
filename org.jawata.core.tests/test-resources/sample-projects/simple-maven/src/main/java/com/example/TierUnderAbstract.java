package com.example;

/**
 * ROW 4 REFUSES this, and the refusal is NARROWER than row 38's. Row 38 declines an abstract
 * parent outright, which is right for a leaf because folding one rewrites every
 * {@code new Sub()}. A collapsed middle level is frequently abstract and never constructed, so
 * this row asks whether anything constructs THIS one — and here the static factory below does,
 * in its own file, which is why the refusal fires.
 *
 * <p>It overrides nothing, deliberately: {@link TierAbstract} declares no abstract member, so
 * the override refusal cannot answer ahead of the one this fixture is for.</p>
 */
public class TierUnderAbstract extends TierAbstract {

    public int depth() {
        return 2;
    }

    public static TierUnderAbstract create() {
        return new TierUnderAbstract();
    }
}
