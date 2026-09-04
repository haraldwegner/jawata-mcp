package com.example;

/**
 * Both calls to note() carry the audit line; only one call to partial() does.
 *
 * <p>describeIt() calls describe() with a local named `tag`, not `label`. Row 26 moving
 * describe()'s statement out must rewrite the NAME `label` to `tag` and leave the string
 * literal "label=" alone — the two are the same word, and only the AST can tell them
 * apart.</p>
 */
public class AuditedCaller {

    public void first(Audited audited) {
        Audited.audits = Audited.audits + 1;
        audited.note();
    }

    public void second(Audited audited) {
        Audited.audits = Audited.audits + 1;
        audited.note();
    }

    public void withAudit(Audited audited) {
        Audited.audits = Audited.audits + 1;
        audited.partial();
    }

    public void describeIt(Audited audited, String tag) {
        audited.describe(tag);
    }

    public void withoutAudit(Audited audited) {
        audited.partial();
    }
}
