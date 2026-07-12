package com.example.nested;

/** Fixture: reached only through root -> mid -> leaf module recursion + a ${project.basedir} source override (the post-22d jawata-mcp shape). */
public class NestedType {
    public String greet() {
        return "nested";
    }
}
