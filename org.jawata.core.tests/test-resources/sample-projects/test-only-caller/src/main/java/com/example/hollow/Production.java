package com.example.hollow;

/**
 * The production side. Its calls are what make {@link Capability} members
 * wired rather than hollow — remove {@link #run()}'s body and the expected
 * finding set grows, which is how this fixture proves the production half of
 * the rule is actually consulted.
 */
public class Production {

    /**
     * Control for the detector's own recursion: {@code run()} is public and
     * called from production ({@link #main(String[])}), so the file that
     * suppresses other findings is not itself a finding.
     */
    public void run() {
        Capability capability = new Capability();
        String label = capability.usedInProduction();
        if (!Capability.LABEL.equals(label)) {
            throw new IllegalStateException(label);
        }
        // Wires ONE of the two render overloads — the other must still be
        // reported, named by its own parameter list.
        capability.render();
    }

    public static void main(String[] args) {
        new Production().run();
    }
}
