package com.example;

import com.example.DerivedVariableTargets.Invoice;

/**
 * The OTHER file — what makes row 45's cross-file claim checkable rather than asserted.
 *
 * <p>It reads a public derived field from outside the declaring file, so a run that deleted
 * the field and rewrote only its own file would leave this reading something gone. Nothing
 * here quotes the operation's output, for the reason row 54's fixture records.</p>
 */
public class DerivedVariableUser {

    public String render(Invoice invoice) {
        return "gross " + invoice.grossAmount;
    }
}
