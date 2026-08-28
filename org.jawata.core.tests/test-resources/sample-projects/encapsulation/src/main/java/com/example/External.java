package com.example;

/**
 * An external mutator: it does not own {@link Account}'s {@code balance} field
 * but changes its value through the public setter — the effective external
 * write that a direct field-write search cannot see.
 */
public class External {

    void poke() {
        Account a = new Account();
        a.setBalance(100);
    }

    /**
     * Constructs a {@link Vault} from outside it (Sprint 28d). This is what makes
     * {@code Vault.limit} — private, non-final, assigned by that constructor — a
     * NEAR MISS for the {@code encapsulation} sweep kind rather than a shape the
     * rule could never reach: it is silent only because a constructor is not a
     * mutator there.
     */
    Vault build() {
        return new Vault("owner", 3);
    }
}
