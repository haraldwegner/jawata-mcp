package com.example;

/** Row 64 (Sprint 28d-rescue): one function doing two jobs in sequence, and two it cannot. */
public class TwoPhase {

    public int priceOrder(int quantity, int unitPrice) {
        int base = quantity * unitPrice;
        int discountLevel = quantity / 500;
        int unused = quantity + 1;
        int discount = discountLevel * 100;
        return base - discount;
    }

    public int earlyExit(int quantity) {
        int base = quantity * 2;
        if (base > 100) {
            return 0;
        }
        return base + 1;
    }

    public int writesBack(int quantity) {
        int base = quantity * 2;
        base = base + 5;
        return base;
    }
}
