# Recorded divergences — move

## 28h z150, 2026-11-27: indentation, class (2) formatting drift

`row-move_static_function` was re-recorded. In a project that declares no formatter the engine now indents with spaces; the recorded
output had tabs. Compared ignoring white space the two are identical.

## 28h z163, 2026-11-27: the moved method's indentation, class (1) the engine was given what it needed

`row-move_static_function` was re-recorded again. The static move had given JDT zero tab and indent widths, so the moved
method's body and closing brace landed one level too deep. With the project's widths they sit one level inside the class.
