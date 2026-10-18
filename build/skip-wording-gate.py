#!/usr/bin/env python3
# jawata-gate: skip-wording
"""Every skip a test can make must be worded so an abort budget accepts it.

WHY THIS EXISTS. build/abort-budget.sh refuses a run in which a test skipped for a reason no
committed pattern covers. It runs AFTER the suite, so the first sign of a skip worded a little
differently was a red job 55 minutes in (4.4.9, 2026-10-18: Windows refused "the fixture is a POSIX
shell script" where the file carries "the brain fixture is a POSIX shell script"). The wording is a
string literal in the test source, so it can be checked in seconds without running a test.

WHAT IT READS. Every .java file that mentions an assumption, comments and fixture projects
(test-resources) left out. For each call of
    assumeTrue(cond, message)   assumeFalse(cond, message)   Assumptions.abort(message)
it takes the message and asks whether ANY pattern of ANY build/expected-aborts.* file is a substring
of it (the abort budget's own rule: a substring of the abort line). The files are unioned, so this
is deliberately weaker than the per-OS budget: a skip that can only fire on Windows is checked
against every file, not only the Windows one. A real Windows run still decides that case.

THREE ANSWERS, NOT TWO.
  OK         a pattern is a substring of the message.
  VIOLATION  the message is made only of string literals and no pattern matches, or the call gives
             no message at all (JUnit then prints a generic line no budget carries).
  UNCHECKED  the message has parts the source cannot show (a variable, a method call) and the
             literal part matches nothing. The reason may live in the part that is not visible, so
             this is listed, never failed. A number of these is the stated limit of a static check.

THE BASELINE. Many skips guard a condition that never holds in CI ("running as root?", "sources are
not present"), so they have no budget pattern and have never fired. build/skip-wording-baseline.txt
lists the violations that existed when this gate was adopted, one `file<TAB>message` per line, keyed
by file and text, not by line. The gate fails on a violation that is NOT in it: a new skip, or an
old one whose wording changed. That is the case that cost a release a red CI. Adding a line to the
baseline is a decision, like adding a pattern to a budget; --update-baseline rewrites it.

Usage:  build/skip-wording-gate.py [--root <repo>] [--update-baseline]
        exit 0 = no new violation, 1 = a new violation
"""
import argparse
import os
import re
import sys

SKIP_DIRS = {".git", "target", "node_modules", "dist", "docs", "evidence", "test-resources"}
CALL = re.compile(r"(?<![\w.])(?:(?:org\.junit\.jupiter\.api\.)?Assumptions\.)?"
                  r"(assumeTrue|assumeFalse|abort)\s*\(")
UNICODE = re.compile(r"\\u([0-9a-fA-F]{4})")
BASELINE = os.path.join("build", "skip-wording-baseline.txt")


def patterns(root):
    found = []
    build = os.path.join(root, "build")
    for name in sorted(os.listdir(build)) if os.path.isdir(build) else []:
        if not name.startswith("expected-aborts."):
            continue
        with open(os.path.join(build, name), encoding="utf-8") as f:
            for line in f:
                line = line.rstrip("\n")
                if line.strip() and not line.lstrip().startswith("#"):
                    found.append(line)
    return found


def java_files(root):
    for base, dirs, files in os.walk(root):
        dirs[:] = [d for d in dirs if d not in SKIP_DIRS]
        for f in files:
            if f.endswith(".java"):
                yield os.path.join(base, f)


def strip_comments(src):
    """(source with every comment blanked, the same with string BODIES masked too).

    Newlines are kept and every replacement has the original length, so a position in one is the
    same position in the other and line numbers survive. Calls are FOUND in the masked copy, so a
    string that merely contains the text `assumeTrue(x)` is not a call; messages are READ from the
    first copy, where the strings are intact."""
    code, masked, i, n = [], [], 0, len(src)

    def both(text, mask_text=None):
        code.append(text)
        masked.append(text if mask_text is None else mask_text)

    while i < n:
        if src.startswith('"""', i):
            end = src.find('"""', i + 3)
            end = n if end < 0 else end + 3
            seg = src[i:end]
            both(seg, "".join("\n" if c == "\n" else "x" for c in seg))
            i = end
        elif src[i] in "\"'":
            q, j = src[i], i + 1
            while j < n and src[j] != q:
                j += 2 if src[j] == "\\" else 1
            seg = src[i:j + 1]
            both(seg, q + "x" * max(len(seg) - 2, 0) + (q if len(seg) > 1 else ""))
            i = j + 1
        elif src.startswith("//", i):
            j = src.find("\n", i)
            j = n if j < 0 else j
            both(" " * (j - i))
            i = j
        elif src.startswith("/*", i):
            j = src.find("*/", i + 2)
            j = n if j < 0 else j + 2
            both("".join("\n" if c == "\n" else " " for c in src[i:j]))
            i = j
        else:
            both(src[i])
            i += 1
    return "".join(code), "".join(masked)


def split_args(src, start):
    """The top-level arguments of the call whose '(' is just before `start`."""
    args, depth, i, cur_start, n = [], 0, start, start, len(src)
    while i < n:
        c = src[i]
        if src.startswith('"""', i):
            end = src.find('"""', i + 3)
            i = (end + 3) if end >= 0 else n
            continue
        if c == '"' or c == "'":
            i += 1
            while i < n and src[i] != c:
                i += 2 if src[i] == "\\" else 1
            i += 1
            continue
        if c in "([{":
            depth += 1
        elif c in ")]}":
            if depth == 0:
                args.append(src[cur_start:i])
                return args
            depth -= 1
        elif c == "," and depth == 0:
            args.append(src[cur_start:i])
            cur_start = i + 1
        i += 1
    return args


STRING = re.compile(r'"""(.*?)"""|"((?:[^"\\\n]|\\.)*)"', re.S)


def literals(expr):
    """(the literal text joined, whether anything BUT string literals and '+' is in the expression)."""
    expr = re.sub(r"^\s*\(\s*\)\s*->", "", expr)           # a Supplier<String> lambda
    parts = []
    for m in STRING.finditer(expr):
        text = m.group(1) if m.group(1) is not None else m.group(2)
        text = UNICODE.sub(lambda u: chr(int(u.group(1), 16)), text)
        text = text.replace('\\"', '"').replace("\\n", "\n").replace("\\t", "\t").replace("\\\\", "\\")
        parts.append(text)
    rest = STRING.sub("", expr)
    dynamic = bool(re.sub(r"[\s+()]", "", rest))
    return "".join(parts), dynamic


def one_line(text):
    return re.sub(r"\s+", " ", text).strip()


def scan(root):
    pats = patterns(root)
    ok, unchecked, violations = 0, [], []
    for path in java_files(root):
        with open(path, encoding="utf-8", errors="replace") as f:
            raw = f.read()
        if "ssum" not in raw and "abort(" not in raw:
            continue
        src, masked = strip_comments(raw)
        rel = os.path.relpath(path, root).replace(os.sep, "/")
        for m in CALL.finditer(masked):
            kind = m.group(1)
            args = split_args(src, m.end())
            line = src.count("\n", 0, m.start()) + 1
            wanted = 1 if kind == "abort" else 2
            if len(args) < wanted or not args[wanted - 1].strip():
                violations.append((rel, line, "(no message: JUnit prints a generic line no budget carries)"))
                continue
            text, dynamic = literals(args[wanted - 1])
            text = one_line(text)
            if any(p in text for p in pats):
                ok += 1
            elif dynamic or not text:
                unchecked.append((rel, line, text or "(built at run time)"))
            else:
                violations.append((rel, line, text))
    return ok, unchecked, violations, len(pats)


def read_baseline(root):
    path = os.path.join(root, BASELINE)
    known = set()
    if os.path.isfile(path):
        with open(path, encoding="utf-8") as f:
            for line in f:
                line = line.rstrip("\n")
                if line and not line.startswith("#"):
                    known.add(tuple(line.split("\t", 1)))
    return known


def write_baseline(root, violations):
    path = os.path.join(root, BASELINE)
    with open(path, "w", encoding="utf-8") as f:
        f.write("# Skips whose wording no build/expected-aborts.* pattern covers, as of adoption of\n"
                "# build/skip-wording-gate.py. Each is a skip whose condition has not fired in CI. Keyed by\n"
                "# file and message, one `file<TAB>message` per line. A line here is a decision, like a\n"
                "# budget pattern: it says 'this skip is known and unbudgeted'.\n")
        for rel, _line, text in sorted({(r, 0, t) for r, _l, t in violations}):
            f.write(f"{rel}\t{text}\n")


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--root", default=os.path.join(os.path.dirname(os.path.abspath(__file__)), ".."))
    ap.add_argument("--update-baseline", action="store_true")
    args = ap.parse_args()
    root = os.path.abspath(args.root)
    ok, unchecked, violations, npat = scan(root)
    if args.update_baseline:
        write_baseline(root, violations)
        print(f"skip wording: baseline rewritten with {len({(r, t) for r, _l, t in violations})} entr(ies)")
        return 0
    known = read_baseline(root)
    new = [v for v in violations if (v[0], v[2]) not in known]
    seen = {(r, t) for r, _l, t in violations}
    stale = sorted(known - seen)
    print(f"skip wording: {ok} skip message(s) match a budget pattern ({npat} pattern(s) across "
          f"build/expected-aborts.*), {len(unchecked)} unchecked (message built at run time), "
          f"{len(violations) - len(new)} known unbudgeted (baseline), {len(new)} NEW violation(s)")
    for rel, line, text in unchecked:
        print(f"  unchecked  {rel}:{line}  {text[:100]}")
    for rel, text in stale:
        print(f"  stale baseline entry (no such skip any more): {rel}  {text[:100]}")
    for rel, line, text in new:
        print(f"  VIOLATION  {rel}:{line}  {text[:160]}")
    if new:
        print("A skip no budget pattern matches is refused by build/abort-budget.sh AFTER the whole suite.")
        print("Word it as an existing pattern, or add its reason to the right build/expected-aborts.<name>")
        print("together with why skipping it is acceptable, or (a skip that never fires) record it with")
        print("build/skip-wording-gate.py --update-baseline.")
        return 1
    return 0


if __name__ == "__main__":
    sys.exit(main())
