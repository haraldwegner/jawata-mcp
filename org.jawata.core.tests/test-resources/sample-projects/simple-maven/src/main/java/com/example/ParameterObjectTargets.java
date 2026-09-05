package com.example;

/**
 * Fixtures for Fowler row 21, Introduce Parameter Object, as
 * {@code change_method_signature kind=introduce_parameter_object}.
 *
 * <p>The canonical shape is a group of parameters that describe ONE thing and travel together.
 * The refusal is a method with a single parameter, which is not a group.</p>
 *
 * <p>No comment here quotes what the operation emits, and none of them spells a declaration a
 * test anchors on — a fixture that contains the string its own test searches for makes the test
 * pass on the comment, which this stage's neighbour learned twice.</p>
 */
public class ParameterObjectTargets {

    /**
     * Three parameters that are one concept: where the delivery goes.
     *
     * <p>Its caller lives in another file on purpose. The engine rewrites call sites across
     * files, and a fixture whose only caller sits beside it would pass whether or not that
     * happened.</p>
     */
    public String describeDelivery(String city, String street, int postcode) {
        return city + "/" + street + "/" + postcode;
    }

    /** One parameter is not a group, and this is the refusal. */
    public String describeCity(String city) {
        return city;
    }
}
