package com.example.hub;

import com.example.ledger.Ledger;
import com.example.model.Customer;
import com.example.model.Money;
import com.example.model.Order;

/**
 * The outlier. Nine connascence sites, every one of them across a package
 * boundary and none within — the expensive pole of Page-Jones's rule.
 *
 * <p>Sites, with their strength rank:</p>
 * <ul>
 *   <li>{@code Ledger} field type — Connascence of Type (2)</li>
 *   <li>{@code new Ledger()} — Connascence of Name (1)</li>
 *   <li>{@code Order} return type — Connascence of Type (2)</li>
 *   <li>{@code Customer} local type — Connascence of Type (2)</li>
 *   <li>{@code new Customer(buyer)} — Connascence of Name (1)</li>
 *   <li>{@code Money} local type — Connascence of Type (2)</li>
 *   <li>{@code new Money(cents)} — Connascence of Name (1)</li>
 *   <li>{@code ledger.post(a, b, c)} — Connascence of Position (4)</li>
 *   <li>{@code new Order(a, b)} — Connascence of Position (4)</li>
 * </ul>
 *
 * <p>Total cross-boundary weight 19 — 3 Name, 4 Type, 2 Position.</p>
 */
public final class Coordinator {

    private final Ledger ledger = new Ledger();

    public Order place(String buyer, long cents) {
        Customer customer = new Customer(buyer);
        Money total = new Money(cents);
        ledger.post(customer, total, "order placed");
        return new Order(customer, total);
    }
}
