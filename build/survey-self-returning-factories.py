#!/usr/bin/env python3
"""Survey a Java corpus for SELF-RETURNING STATIC FACTORIES, with the method kept.

    build/survey-self-returning-factories.py [<corpus-root>]

Default corpus: the pinned fork at /home/harald/CursorProjects/java-design-patterns.

WHY THIS SCRIPT EXISTS AS A FILE RATHER THAN A COMMAND SOMEBODY RAN
-------------------------------------------------------------------
Sprint 28d Stage 9 justified deviating from D3's stated direction with a survey of
this shape, reported as "six sites, in four classes, every one carries Lombok". The
C9 auditor re-ran it and found NINE sites in SIX classes, one of which — monad's
`Validator.of` — carries no Lombok at all. The claim that licensed the deviation was
false, and it went through the stage, an architect watch-diff and the dossier
unchallenged because the method was never written down. Four artifacts said "the
fork was surveyed" and none said how.

THE DEFECTS THIS SCRIPT HAS ALREADY HAD, kept because they are the reusable part
---------------------------------------------------------------------------------
TWO WRONG NUMBERS BEFORE THIS ONE. Both were reported as measurements.

(1) BLIND TO GENERICS. The first version matched `static <ClassName> <method>(`.
    Real factories are generic:

        public static <T> Validator<T> of(T t)           # monad/Validator.java
        public static <K> ChapterResult<K> success(K v)  # saga/.../ChapterResult.java

    The type-parameter list between `static` and the return type, and the type
    ARGUMENTS on the return type, both broke the pattern. The instrument was blind
    to the form the pattern is normally written in, and its silence was
    indistinguishable from absence.

(2) A FIXED LOOKAHEAD IS NOT A METHOD BODY, and an anonymous class is not a
    constructor call. The second version searched a 600-character window after the
    declaration for `return new <Cls>`. On `trampoline/Trampoline.java` that window
    ran straight out of `done()` — whose body is `return () -> result;` — and into
    the NEXT method, so `done` was reported as a factory it is not. And the match it
    found there, `return new Trampoline<T>() {`, is an ANONYMOUS CLASS, not a call
    to a constructor. `Trampoline` is an INTERFACE and has no constructor at all.

    So the second version reported two dependency-free sites that do not exist, and
    they were the two it recommended.

This version brace-matches the method's own body, refuses anonymous instantiation,
and refuses any enclosing type that is not a `class`. State what each exclusion is
for when adding one: the failure above was not a typo, it was a heuristic standing
in for a parse.

WHAT COUNTS AS THE SHAPE
------------------------
A static method whose declared return type is the enclosing class (with or without
type arguments) and whose body returns `new <ThatClass>(...)`. That is the only
factory shape `replace_constructor_with_factory` produces, so it is the only one
whose round trip can close against a human-written original.

Reported per site: the module, the class, the method, whether the FILE imports
Lombok, and the enclosing class's CONSTRUCTOR VISIBILITY — because a private
constructor blocks the AWAY-first direction independently of any dependency (the
trip is only defined where the old path stays open, which is why the tests pass
protectConstructor=false).
"""
import os
import re
import sys

DEFAULT_ROOT = "/home/harald/CursorProjects/java-design-patterns"


def body_of(src, open_brace_index):
    """The method's OWN body, by brace matching — not a fixed lookahead.

    A character window is not a scope. On an interface whose one-line method is
    followed by another, a window walks into the next method and attributes its
    body to the first. That is defect (2) above, and it produced two recommended
    sites that do not exist.
    """
    depth = 0
    for i in range(open_brace_index, len(src)):
        c = src[i]
        if c == "{":
            depth += 1
        elif c == "}":
            depth -= 1
            if depth == 0:
                return src[open_brace_index + 1:i]
    return ""


def survey(root):
    hits = []
    for dirpath, _dirnames, filenames in os.walk(root):
        if os.sep + "test" + os.sep in dirpath or os.sep + ".git" in dirpath:
            continue
        for fn in sorted(filenames):
            if not fn.endswith(".java"):
                continue
            cls = fn[:-5]
            path = os.path.join(dirpath, fn)
            try:
                src = open(path, encoding="utf-8", errors="replace").read()
            except OSError:
                continue

            # ONLY A CLASS. An interface's static factory returns a lambda or an
            # anonymous implementation and the type has no constructor at all, so
            # it cannot be an original for a trip whose AWAY leg inlines a
            # constructor wrapper.
            if not re.search(r"\b(?:public\s+|final\s+|abstract\s+)*class\s+"
                             + re.escape(cls) + r"\b", src):
                continue

            # static [<TYPE PARAMS>] ClassName[<ARGS>] method(...) {
            #        ^ the two things the first survey could not see
            decl = re.compile(
                r"\bstatic\s+"
                r"(?:<[^>]*>\s+)?"                      # optional type-parameter list
                r"(?:final\s+)?"
                + re.escape(cls) +
                r"\s*(?:<[^>{;]*>)?\s+"                 # optional type arguments
                r"(\w+)\s*\([^)]*\)\s*(?:throws [^{]+)?\{"
            )
            for m in decl.finditer(src):
                body = body_of(src, m.end() - 1)
                # `new X(...)` but NOT `new X(...) { ... }` — an anonymous subclass
                # is not a call to X's constructor, and inlining cannot produce one.
                ctor_call = re.compile(
                    r"return\s+new\s+" + re.escape(cls)
                    + r"\s*(?:<[^>]*>)?\s*\([^;]*\)\s*;")
                if not ctor_call.search(body):
                    continue
                module = os.path.relpath(path, root).split(os.sep)[0]
                imports = re.findall(r"^import\s+(?:static\s+)?([\w.]+);", src, re.M)
                lombok = [i for i in imports if i.startswith("lombok")]
                other = [i for i in imports
                         if not i.startswith(("java.", "javax.", "lombok"))]
                ctors = re.findall(
                    r"^\s*(public|protected|private)?\s*" + re.escape(cls)
                    + r"\s*\([^)]*\)\s*\{", src, re.M)
                vis = sorted({c if c else "package-private" for c in ctors}) or ["implicit"]
                hits.append({
                    "module": module, "cls": cls, "method": m.group(1),
                    "lombok": lombok, "other": other, "ctor": vis,
                    "path": os.path.relpath(path, root),
                })
    return hits


def main():
    root = sys.argv[1] if len(sys.argv) > 1 else DEFAULT_ROOT
    hits = survey(root)
    print(f"corpus: {root}")
    # Counted by PATH, not by simple name: saga has two distinct classes both
    # called Saga, in different packages. Counting by name reports five where
    # there are six, which is the kind of quiet undercount this file exists to
    # stop repeating.
    print(f"self-returning static factories: {len(hits)} "
          f"in {len({h['path'] for h in hits})} classes\n")
    clean = 0
    for h in hits:
        marks = []
        if h["lombok"]:
            marks.append("LOMBOK(" + ",".join(sorted(h["lombok"])) + ")")
        if h["other"]:
            marks.append(f"{len(h['other'])} other external import(s)")
        if not marks:
            marks.append("NO EXTERNAL DEPENDENCY")
            clean += 1
        print(f"  {h['module']:28s} {h['cls']}.{h['method']}()")
        print(f"  {'':28s} ctor: {'/'.join(h['ctor']):16s} {'; '.join(marks)}")
        print(f"  {'':28s} {h['path']}")
    print(f"\ndependency-free sites: {clean} of {len(hits)}")
    usable = [h for h in hits
              if not h["lombok"] and not h["other"] and "private" not in h["ctor"]]
    print(f"sites usable as an AWAY-FIRST original "
          f"(no external dependency AND a reachable constructor): {len(usable)}")
    for h in usable:
        print(f"  -> {h['module']}/{h['cls']}.{h['method']}()  ctor {'/'.join(h['ctor'])}")


if __name__ == "__main__":
    main()
