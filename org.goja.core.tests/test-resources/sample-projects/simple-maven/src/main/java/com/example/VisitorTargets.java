package com.example;

/**
 * Sprint 19 fixture — refactor_to_visitor. Shape (0-based line 10) is an abstract
 * base with three subtypes; refactor_to_visitor introduces a ShapeVisitor
 * interface + accept() double-dispatch across the hierarchy.
 */
public class VisitorTargets {
}

abstract class Shape {
}

class Circle extends Shape {
    double radius;
}

class Square extends Shape {
    double side;
}

class Triangle extends Shape {
    double base;
    double height;
}
