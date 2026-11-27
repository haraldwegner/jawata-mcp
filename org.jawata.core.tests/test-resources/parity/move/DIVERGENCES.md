# Recorded divergences — move

## 28h z150, 2026-11-27: indentation, class (2) formatting drift

`row-move_static_function` was re-recorded. In a project that declares no formatter the engine now indents with spaces; the recorded
output had tabs. Compared ignoring white space the two are identical.

The recorded method body and closing brace sit one level deeper than they should, as they did with tabs: a separate
fault (28h z163), not part of this change.
