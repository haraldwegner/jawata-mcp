package org.jawata.mcp.learn;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

/** Sprint 28h item 9 (i) slice 1: what a pm may enter as one step of a sprint plan. */
class PlannedStepTest {

    private static final ObjectMapper JSON = new ObjectMapper();

    private static JsonNode empty() {
        return JSON.createObjectNode();
    }

    @Test
    @DisplayName("a person step that carries a tool is refused")
    void aPersonStepWithAToolIsRefused() {
        assertThrows(IllegalArgumentException.class,
            () -> new PlannedStep("sign off", "compile_workspace", null, null, false, true));
    }

    @Test
    @DisplayName("a person step that carries arguments is refused")
    void aPersonStepWithArgumentsIsRefused() {
        assertThrows(IllegalArgumentException.class,
            () -> new PlannedStep("sign off", null, empty(), null, false, true));
    }

    @Test
    @DisplayName("a person step marked checkpoint is refused")
    void aPersonStepMarkedCheckpointIsRefused() {
        assertThrows(IllegalArgumentException.class,
            () -> new PlannedStep("sign off", null, null, null, true, true));
    }

    @Test
    @DisplayName("a checked step with a blank tool is refused")
    void aCheckedStepWithABlankToolIsRefused() {
        assertThrows(IllegalArgumentException.class,
            () -> new PlannedStep("compile", "  ", empty(), null, false, false));
    }

    @Test
    @DisplayName("a checked step with null arguments is refused")
    void aCheckedStepWithNullArgumentsIsRefused() {
        assertThrows(IllegalArgumentException.class,
            () -> new PlannedStep("compile", "compile_workspace", null, null, false, false));
    }

    @Test
    @DisplayName("a blank title is refused")
    void aBlankTitleIsRefused() {
        assertThrows(IllegalArgumentException.class,
            () -> new PlannedStep("   ", "compile_workspace", empty(), null, false, false));
    }

    @Test
    @DisplayName("a person step that carries an expect object is refused")
    void aPersonStepWithAnExpectIsRefused() {
        assertThrows(IllegalArgumentException.class,
            () -> new PlannedStep("sign off", null, null, empty(), false, true));
    }

    @Test
    @DisplayName("a Reserved with after below zero is refused")
    void aReservedWithAfterBelowZeroIsRefused() {
        assertThrows(IllegalArgumentException.class, () -> new PlanMarks.Reserved("sign off", -1));
    }

    @Test
    @DisplayName("a Reserved with a blank title is refused")
    void aReservedWithABlankTitleIsRefused() {
        assertThrows(IllegalArgumentException.class, () -> new PlanMarks.Reserved("  ", 0));
    }

    @Test
    @DisplayName("a PlanMarks with a blank name is refused")
    void aPlanMarksWithABlankNameIsRefused() {
        assertThrows(IllegalArgumentException.class,
            () -> new PlanMarks("  ", List.of("A"), List.of(), List.of()));
    }

    @Test
    @DisplayName("a PlanMarks does not change when the lists passed to it are changed afterwards")
    void aPlanMarksKeepsItsOwnCopyOfTheLists() {
        List<String> titles = new ArrayList<>(List.of("A"));
        List<Integer> checkpoints = new ArrayList<>(List.of(0));
        List<PlanMarks.Reserved> reserved = new ArrayList<>(List.of(new PlanMarks.Reserved("P", 1)));
        PlanMarks marks = new PlanMarks("sprint", titles, checkpoints, reserved);

        titles.add("B");
        checkpoints.add(1);
        reserved.add(new PlanMarks.Reserved("Q", 2));

        assertEquals(List.of("A"), marks.titles());
        assertEquals(List.of(0), marks.checkpoints());
        assertEquals(List.of(new PlanMarks.Reserved("P", 1)), marks.reserved());
    }

    @Test
    @DisplayName("a checked step with empty-object arguments and a null expect is accepted and keeps what it was given")
    void aCheckedStepWithEmptyArgumentsAndNoExpectIsAccepted() {
        JsonNode arguments = empty();
        PlannedStep step = new PlannedStep("compile", "compile_workspace", arguments, null, false, false);
        assertEquals("compile", step.title());
        assertEquals("compile_workspace", step.tool());
        assertEquals(arguments, step.arguments());
    }

    @Test
    @DisplayName("a person step with only a title is accepted")
    void aPersonStepWithOnlyATitleIsAccepted() {
        PlannedStep step = new PlannedStep("sign off", null, null, null, false, true);
        assertEquals("sign off", step.title());
    }

    @Test
    @DisplayName("a null title is refused with a NullPointerException")
    void aNullTitleIsRefusedWithANullPointerException() {
        assertThrows(NullPointerException.class,
            () -> new PlannedStep(null, "compile_workspace", empty(), null, false, false));
    }
}
