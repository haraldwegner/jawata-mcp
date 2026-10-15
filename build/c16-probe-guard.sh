#!/usr/bin/env bash
# The containment probe's own-log guard (S16-28), as a function a test can drive.
#
# The probe measures whether a brain wrote into a throwaway work tree. A probe whose own log sits
# INSIDE that tree reports every cell as a breach, and makes its timeout and empty-answer verdicts
# unreachable. The log is kept outside; this is the check that keeps it outside.
#
# log_is_outside_tree <log-path> <work-tree-path>
#   returns 0 when the log is NOT inside the tree, 1 when it is. A sibling that merely shares a name
#   prefix ("/o/work-1x/log" against "/o/work-1") is outside, which is why the pattern ends in "/".
log_is_outside_tree() {
    case "$1" in
        "$2"/*) return 1 ;;
    esac
    return 0
}
