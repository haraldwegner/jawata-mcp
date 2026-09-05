package com.example;

/**
 * Row 16 (Hide Delegate) fixtures — Fowler's own example, plus the shapes the operation
 * must REFUSE.
 *
 * <p>One file rather than additions to an existing fixture, deliberately. `simple-maven` is
 * shared and growing, and Stage 6 recorded what that costs: its row fixtures added enough
 * clone groups at minTokens=5 to push the group four clone-detection tests assert about off
 * the default result page, so the detector was right four times and four tests went red.
 * Distinct method bodies here, and no shape repeated.</p>
 */
public class HideDelegateTargets {

    /** The server. A client reaching through it to the manager is the smell. */
    public static class Person {
        private final Department department;

        public Person(Department department) {
            this.department = department;
        }

        public Department getDepartment() {
            return department;
        }
    }

    /** The delegate the client should not have to know about. */
    public static class Department {
        private final String manager;
        private final int headcount;

        public Department(String manager, int headcount) {
            this.manager = manager;
            this.headcount = headcount;
        }

        public String getManager() {
            return manager;
        }

        public int budgetFor(int months) {
            return headcount * months * 1000;
        }

        /** Returns a type from ANOTHER package — see leadOf below. */
        public com.example.service.Lead getLead() {
            return new com.example.service.Lead(manager);
        }
    }

    /** THE CANONICAL CASE: two calls deep, no arguments, both types editable here. */
    public String managerOf(Person john) {
        return john.getDepartment().getManager();
    }

    /**
     * REFUSAL — the intermediate call is fine but the HIDDEN one takes an argument.
     *
     * <p>This one is NOT refused: the forwarder carries the parameter through, which is why
     * the generated signature is built from the outer call's binding rather than assumed
     * empty. It is here so that path is exercised rather than asserted.</p>
     */
    public int budgetOf(Person john, int months) {
        return john.getDepartment().budgetFor(months);
    }

    /**
     * REFUSAL — the server type is outside this workspace.
     *
     * <p>{@code getClass()} is declared on {@code java.lang.Object}, so the forwarder would
     * have to be added to the JDK. This is the commonest refusal on real code and the reason
     * it is worth a fixture: the SHAPE is a perfect match, and only the source's absence
     * distinguishes it.</p>
     */
    public String typeNameOf(Object anything) {
        return anything.getClass().getName();
    }

    /** NOT A CHAIN — one call deep. There is no delegate to hide. */
    public Department departmentOf(Person john) {
        return john.getDepartment();
    }

    /** A fluent builder: {@code create()} is STATIC, so there is no receiver to hide behind. */
    public static class Ticket {
        private String label = "";

        public static Ticket create() {
            return new Ticket();
        }

        public Ticket label(String value) {
            this.label = value;
            return this;
        }

        public String label() {
            return label;
        }
    }

    /**
     * REFUSAL — the fluent-builder shape, and the fork corpus says it is the common one.
     *
     * <p>Censusing all 84 {@code message_chains} findings in {@code java-design-patterns}
     * found 41 JDK pipelines and 43 fluent builders, and NOT ONE chain where a client reaches
     * through a domain object to a second one. So this fixture carries the shape the detector
     * actually reports most often, and pins that the operation declines it for the right
     * reason rather than generating a forwarder that drops the builder.</p>
     */
    public Ticket urgentTicket() {
        return Ticket.create().label("urgent");
    }

    /**
     * CROSS-PACKAGE: the hidden call returns {@code com.example.service.Lead}, which the server's
     * file does not import. The forwarder generated on Person needs that import, and the
     * first version of this row wrote a bare simple name instead — so the pipeline's compile
     * gate refused the change and the operation declined on a whole class of correct input.
     */
    public com.example.service.Lead leadOf(Person john) {
        return john.getDepartment().getLead();
    }
}
