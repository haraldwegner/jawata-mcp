package com.example;

/**
 * CONTROL FOUR, half 1 — siblings under one parent that re-derived a job anyway.
 *
 * <p>This pair is the reason the detector asks whether a signature is IMPOSED rather than
 * whether the two classes share a supertype. The plan's own wording was "no common
 * supertype", and measuring it against the population this deliverable exists to find
 * overturned it: jawata's ~34 {@code parse} helpers sit very largely on classes that share
 * {@code AbstractApplyingRefactoringTool}, so a shared-supertype exclusion silences the
 * flagship case completely.</p>
 *
 * <p>{@link SharedBase} declares nothing resembling {@code label}, so nothing dictated this
 * shape — two people wrote the same private helper under one parent. It MUST be reported,
 * and the mutation that restores the shared-supertype rule is what proves the rule here is
 * load-bearing rather than a preference.</p>
 */
public class LeftBranch extends SharedBase {

    private static String label(Catalogue catalogue) {
        Item item = new Item();
        return item.label() + ":" + catalogue.size();
    }

    public String caption(Catalogue catalogue) {
        return label(catalogue);
    }
}
