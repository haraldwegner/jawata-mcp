/*
Copyright 2019 Example GmbH. All rights reserved.

This header deliberately has NO leading asterisks, which is legal and not rare.
class hierarchy notes and the record of changes live in CHANGES.md.

Those two lines are the trap. A scan that decides "the type starts here" by
matching RAW lines stops at one of them, concludes this file is in the default
package, and mounts THIS directory as a source root — so the class declared
below lands in the wrong package and does not resolve. It lives in its own tree
precisely so no other root can cover for it.
*/
package com.acme.headered;

/** Sprint 28 (C1, consolidated audit): the comment-aware package scan. */
public class Headered {
    /** Returns a marker. */
    public String marker() { return "headered"; }
}
