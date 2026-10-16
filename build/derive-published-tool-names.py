#!/usr/bin/env python3
"""Derive build/published-tool-names.txt: every tool name this product has ever published.

WHY THIS EXISTS. The product's tool surface was collapsed into front doors more than once, and every retired
name stayed in somebody's instructions: the README, the studio's help, a deployed rules file, an agent's own
memory. The answer to "what did we ever publish" lived nowhere, so the table that points a retired name at its
replacement was written from memory and was incomplete: 35 published names answered a bare "Tool not found"
and the first four patches each fixed one copy of the surface where a failure happened to expose it.

WHAT IT READS. For each release tag, and for the working tree, the tools that release REGISTERED: every
`new XyzTool(` in the registration sources (the application class, `ShippedTools`, `RefactoringDoors`),
mapped to the literal that tool's `getName()` returns. A tool that is built but never registered is not
published. The result agrees with each release's own README claim wherever that README was kept current
(85 tools at v1.0.0, 40 at v1.4.0, 43 at v2.7.0, 42 at v4.1.0, 44 at v4.4.7).

WHAT IT WRITES. One line per name: `name <TAB> live|retired <TAB> first-tag <TAB> last-tag`, sorted. `live`
means the working tree registers it now. `PublishedToolNamesTest` checks the file against the real registry in
both directions and checks that the retired-name table holds exactly the retired names, so a stale file or a
missing pointer fails a test instead of a client.

WHEN TO RUN IT. At each release, from the repository root, after the release's tag history is final:

    python3 build/derive-published-tool-names.py

It is idempotent: a name that is already in the file keeps its first tag.
"""
import re
import subprocess
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
OUT = ROOT / "build" / "published-tool-names.txt"

NAME = re.compile(r'public\s+String\s+getName\s*\(\s*\)\s*\{\s*return\s+"([a-z][a-z0-9_]*)"\s*;', re.S)
NEW = re.compile(r'new\s+(?:[\w.]*\.)?(\w+Tool)\s*\(')
REGISTRATION_FILES = ("JawataApplication.java", "ShippedTools.java", "RefactoringDoors.java")


def git(*args):
    return subprocess.run(["git", "-C", str(ROOT), *args], capture_output=True, text=True, check=True).stdout


def is_source(path):
    return path.endswith(".java") and "/src/" in path and "/tests" not in path


def registered_at(ref):
    """Names registered by the tree at `ref` (a tag), or by the working tree when ref is None."""
    if ref is None:
        paths = [str(p.relative_to(ROOT)) for p in ROOT.glob("org.*/src/**/*.java")]
        read = lambda p: (ROOT / p).read_text(encoding="utf-8")
    else:
        paths = [p for p in git("ls-tree", "-r", "--name-only", ref).splitlines() if is_source(p)]
        read = lambda p: git("show", f"{ref}:{p}")
    paths = [p for p in paths if is_source(p)]
    by_class = {}
    for p in paths:
        base = p.rsplit("/", 1)[-1][:-5]
        if base.endswith("Tool"):
            m = NAME.search(read(p))
            if m:
                by_class[base] = m.group(1)
    classes = set()
    for p in paths:
        base = p.rsplit("/", 1)[-1]
        if base in REGISTRATION_FILES or base.endswith("Application.java"):
            classes.update(NEW.findall(read(p)))
    return {by_class[c] for c in classes if c in by_class}


def main():
    tags = [t for t in git("tag", "--sort=version:refname").split() if re.match(r"^v\d", t)]
    first, last = {}, {}
    for tag in tags:
        for name in registered_at(tag):
            first.setdefault(name, tag)
            last[name] = tag
    live = registered_at(None)
    for name in live:
        first.setdefault(name, "next")
        last[name] = "next"
    rows = []
    for name in sorted(first):
        state = "live" if name in live else "retired"
        rows.append(f"{name}\t{state}\t{first[name]}\t{last[name]}")
    header = [
        "# Every tool name this product has ever published, one per line: name, live|retired, first tag, last tag.",
        "# Derived by build/derive-published-tool-names.py from each release's registered tools; checked against",
        "# the real registry by PublishedToolNamesTest. Do not edit by hand: re-run the script at each release.",
        "# 'next' is the tree that has not been tagged yet.",
    ]
    OUT.write_text("\n".join(header + rows) + "\n", encoding="utf-8")
    n_live = sum(1 for r in rows if r.split("\t")[1] == "live")
    print(f"{OUT.relative_to(ROOT)}: {len(rows)} names, {n_live} live, {len(rows) - n_live} retired")


if __name__ == "__main__":
    sys.exit(main())
