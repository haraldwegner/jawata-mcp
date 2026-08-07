package com.example.hollow;

/**
 * The test side — a plain class on purpose, and NOT named {@code *Test}. It
 * carries no JUnit import, no annotation, and no name signal, so nothing about
 * this FILE says "test" except the source root the importer recorded. A
 * detector that grew its own name heuristic would find nothing to go on here
 * and the expected findings would collapse — which is the point.
 *
 * <p>Two callers of {@code enable()}, matching the founding failure's shape:
 * a member with several callers, every one of them here.</p>
 */
public class ExerciseHarness {

    public void exercisesTheCapability() {
        Capability capability = new Capability();
        capability.enable();
        capability.usedInProduction();
    }

    public void exercisesItAgain() {
        Capability capability = new Capability();
        capability.enable();
        if (capability.hollowField != 7) {
            throw new AssertionError(capability.toString());
        }
    }

    public void reachesTheInterfaceAndTheEntryPoint() {
        Plugin plugin = new Capability();
        plugin.go();
        Capability.main(new String[0]);
    }

    public void exercisesOnlyOneOverload() {
        // render(int) is reached from here and nowhere else; render() is
        // production's. The finding must name which.
        new Capability().render(3);
    }
}
