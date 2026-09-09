package com.example.undeclared;

import java.util.ArrayList;
import java.util.List;

/**
 * Holds the method the mcp#69 reproduction resolves: its signature NAMES {@link Vehicle},
 * so at a compiler level that has no records it has no binding at all.
 */
public final class VehicleReports {

    private VehicleReports() {
    }

    /** Named in the reproduction by string; renaming it needs BuildSystemLoadTest changed too. */
    public static List<Vehicle> builtAfter(List<Vehicle> vehicles, int year) {
        List<Vehicle> kept = new ArrayList<>();
        for (Vehicle vehicle : vehicles) {
            if (vehicle.year() > year) {
                kept.add(vehicle);
            }
        }
        return kept;
    }
}
