package com.example.ledger;

import com.example.model.Customer;
import com.example.model.Money;

/**
 * Moderate cross-boundary connascence: two Connascence-of-Type parameters and two
 * Connascence-of-Name calls into com.example.model. Cross-boundary weight
 * 2 + 2 + 1 + 1 = 6, over 4 sites. {@code String} and {@code long} are outside the
 * corpus, so they are not our encapsulation boundary and are not counted.
 */
public final class Ledger {

    private long posted;

    public void post(Customer customer, Money amount, String memo) {
        posted += amount.cents() + customer.name().length() + memo.length();
    }

    public long posted() {
        return posted;
    }
}
