package com.example;

/**
 * Sprint 19 fixture — form_template_method. HtmlReport.build() (0-based method
 * line 15) and TextReport.build() share a skeleton and differ only in one step
 * (the `body` line). form_template_method pulls the template into Report and makes
 * the varying step an abstract method.
 */
public class TemplateMethodTargets {
}

abstract class Report {
}

class HtmlReport extends Report {
    String build() {
        String header = "== start ==";
        String body = renderHtml();
        String footer = "== end ==";
        return header + body + footer;
    }

    String renderHtml() {
        return "<p>hi</p>";
    }
}

class TextReport extends Report {
    String build() {
        String header = "== start ==";
        String body = renderText();
        String footer = "== end ==";
        return header + body + footer;
    }

    String renderText() {
        return "hi";
    }
}
