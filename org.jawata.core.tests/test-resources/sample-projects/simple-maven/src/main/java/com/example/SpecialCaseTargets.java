package com.example;

/**
 * Row 22 (Introduce Special Case) fixtures — the canonical case and each shape refused.
 *
 * <p>A new file in an EXISTING package, deliberately. Adding a package to this shared sample
 * project once took it over the minimum sample {@code AnalyzeNamingToolTest} relies on and
 * turned that test red; the rule earned there is that a fixture must not move a census some
 * other test measures. Method bodies are distinct for the same reason on the clone axis.</p>
 */
public class SpecialCaseTargets {

    /** THE CANONICAL CASE: a collaborator clients keep checking for absence. */
    public static class Customer {
        private final String name;
        private final int discountPercent;

        public Customer(String name, int discountPercent) {
            this.name = name;
            this.discountPercent = discountPercent;
        }

        public String getName() {
            return name;
        }

        public int getDiscountPercent() {
            return discountPercent;
        }

        public boolean isPreferred() {
            return discountPercent > 10;
        }

        public void recordVisit() {
            // a real customer would log the visit
        }
    }

    /** REFUSAL — final, so nothing can subclass it. */
    public static final class SealedRecordId {
        private final long value;

        public SealedRecordId(long value) {
            this.value = value;
        }

        public long value() {
            return value;
        }
    }

    /** REFUSAL — an interface: a special case for one is an implementation, not a subclass. */
    public interface Billable {
        int amountDue();
    }

    /** REFUSAL — every constructor is private, so a subclass cannot reach one. */
    public static class Registry {
        private Registry() {
        }

        public static Registry open() {
            return new Registry();
        }

        public String describe() {
            return "registry";
        }
    }

    /** The duplicated absence check the special case exists to remove. */
    public String greet(Customer customer) {
        return customer == null ? "Hello, guest" : "Hello, " + customer.getName();
    }
}
