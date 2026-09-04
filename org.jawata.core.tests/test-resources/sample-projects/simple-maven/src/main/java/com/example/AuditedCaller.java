package com.example;

/** Both calls to note() carry the audit line; only one call to partial() does. */
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

    public void withoutAudit(Audited audited) {
        audited.partial();
    }
}
