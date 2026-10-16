#!/usr/bin/env bash
#
# THE PATCH-STREAK GATE — Sprint 28d, S9a.2; made a QUESTION, not a block, on 2026-10-16.
#
# A fix whose failure is DIFFERENT from the last one is not fixing the defect,
# it is moving it. Two in a row means stop fixing and run the architect.
#
# Harald, 2026-08-30, correcting how the alarm had been read for weeks:
#
#   "every time you made a change, something different failed. This is what
#    this should be about. If you make a fix the symptom is not the same but
#    something different breaks."
#
# Harald, 2026-10-16, after it fired on six enhancement releases in a row (4.4.1 to
# 4.4.6) and cost an hour and a second question after he had already said "release":
#
#   "This is just for bug fixing, to detect if we have multiple patches for the same
#    or related bug showing some design smell. We just iterate through the new
#    enhancements here. And even in bug fixing we could move through complete sets of
#    independent bugs. This should only run when working on a single fix or the last
#    fix breaks something at the next level."
#
# WHAT WAS WRONG. This gate counts TAG NUMBERS, and this project ships enhancement
# iterations as patch numbers, so a normal week of feature work looks exactly like a
# fix streak. A script cannot tell the two apart. The first fix (2026-09-01) narrowed
# the count to one major.minor and still could not. So the gate no longer tries to
# decide: it puts the evidence in front of whoever is releasing and ASKS.
#
# HOW IT WORKS NOW
#   1. Called with only a tag, on a patch release with neighbours, it prints each
#      neighbouring release with the commit subjects it carried, and the question:
#      is this ENHANCEMENT iteration, or a FIX SERIES on one defect, or did the last
#      fix break something at the next level? Exit 4 (ASK). Nothing is blocked.
#   2. Called again with --kind and --because, the answer is checked for EVIDENCE: the
#      --because text must name every neighbouring release, one line each, saying
#      what it was. An answer that skips one is refused (exit 3).
#   3. An ENHANCEMENT answer passes (exit 0) and is APPENDED to the log
#      (docs/release-notes/patch-streak-answers.log of the repo being released: when, tag, kind, the evidence), so a false
#      "enhancement" is on the record, next to the commits that contradict it. A
#      FIX-SERIES or BROKE-NEXT answer is the original alarm and exits 1: stop, run
#      the architect over the WHOLE run, and put it in front of Harald.
#
# WHY IT LIVES IN THE RELEASE STEP AND NOT IN MEMORY. An agent forgets after a
# compaction. The question is asked by the tool, with the evidence assembled by the
# tool, from the release command (~/.claude/commands/release.md, Step 0), BEFORE any
# long gate and BEFORE anyone is asked anything. It is a dev-machine check (his ruling,
# 2026-09-02): it is not in CI and run-suite.sh does not call it.
#
# THE OVERRIDE IS STILL HIS. STREAK_OVERRIDE=<his words> clears any outcome and is the
# RECORD of his word, never a substitute for it.
#
# USAGE
#   build/patch-streak-gate.sh <tag> [--explain]
#   build/patch-streak-gate.sh <tag> --kind enhancement|fix-series|broke-next --because "<text>"
#
# EXIT
#   0  not a streak candidate, or answered ENHANCEMENT with evidence, or overridden by him
#   1  STREAK — answered FIX-SERIES or BROKE-NEXT: stop, run the architect over the run
#   2  misuse (bad tag, not a git repo, unknown --kind)
#   3  the answer did not carry the evidence (a neighbouring release is not named)
#   4  ASK — the question and the evidence were printed; answer and call again
#
set -uo pipefail

WINDOW_DAYS="${STREAK_WINDOW_DAYS:-7}"
# The answers are logged in the repo being RELEASED (the one the gate is run from), beside its release
# notes, so the release commit carries them: docs/release-notes/patch-streak-answers.log.
LOG="${STREAK_LOG:-$(git rev-parse --show-toplevel 2>/dev/null || pwd)/docs/release-notes/patch-streak-answers.log}"

die() { echo "patch-streak-gate: $*" >&2; exit 2; }

TAG=""
EXPLAIN=false
KIND=""
BECAUSE=""
while [ "$#" -gt 0 ]; do
    case "$1" in
        --explain) EXPLAIN=true ;;
        --kind)    shift; KIND="${1:-}" ;;
        --because) shift; BECAUSE="${1:-}" ;;
        -*)        die "unknown option '$1'" ;;
        *)         [ -z "$TAG" ] && TAG="$1" || die "more than one tag given" ;;
    esac
    shift
done
[ -n "$TAG" ] || die "usage: $0 <tag> [--explain] [--kind enhancement|fix-series|broke-next --because TEXT]"
case "$KIND" in
    ""|enhancement|fix-series|broke-next) ;;
    *) die "--kind must be enhancement, fix-series or broke-next, not '$KIND'" ;;
esac

git rev-parse --git-dir >/dev/null 2>&1 || die "not a git repository"

# --- the version must parse, or we are not judging what we think we are ---
if ! [[ "$TAG" =~ ^v?([0-9]+)\.([0-9]+)\.([0-9]+)$ ]]; then
    die "tag '$TAG' is not vMAJOR.MINOR.PATCH — refusing to guess"
fi
PATCH="${BASH_REMATCH[3]}"
STREAK_SERIES="${BASH_REMATCH[1]}.${BASH_REMATCH[2]}."

# A minor or major bump is a FEATURE release and is never a streak member.
if [ "$PATCH" -eq 0 ]; then
    echo "OK  $TAG is a feature release (patch component 0) — not a streak candidate."
    exit 0
fi

# --- when was this tag made? An unmade tag is judged as "now": the gate must
# --- work BEFORE the tag exists, which is when the release step calls it.
TAG_EPOCH=$(git log -1 --format=%ct "$TAG" 2>/dev/null)
if [ -z "$TAG_EPOCH" ]; then
    TAG_EPOCH=$(date +%s)
    WHEN="now (tag does not exist yet)"
    TAG_REF="HEAD"
else
    WHEN=$(date -u -d "@$TAG_EPOCH" +%Y-%m-%d\ %H:%M)
    TAG_REF="$TAG"
fi
CUTOFF=$(( TAG_EPOCH - WINDOW_DAYS * 86400 ))

# --- the OTHER PATCHES ON THIS SAME MINOR inside the trailing window ---
NEIGHBOURS=()
while read -r t; do
    [ "$t" = "$TAG" ] && continue
    case "$t" in
        "v${STREAK_SERIES}"*) ;;
        *) continue ;;
    esac
    [ "${t##*.}" = "0" ] && continue
    e=$(git log -1 --format=%ct "$t" 2>/dev/null) || continue
    [ -z "$e" ] && continue
    # Strictly BEFORE this tag, and inside the window.
    if [ "$e" -ge "$CUTOFF" ] && [ "$e" -lt "$TAG_EPOCH" ]; then
        NEIGHBOURS+=("$t|$(date -u -d "@$e" +%Y-%m-%d\ %H:%M)")
    fi
done < <(git tag -l 'v[0-9]*.[0-9]*.[0-9]*' | sort -V)

if [ "${#NEIGHBOURS[@]}" -eq 0 ]; then
    echo "OK  $TAG is a patch with no other patch on v${STREAK_SERIES}x inside ${WINDOW_DAYS} days."
    exit 0
fi

# --- THE EVIDENCE: what each release carried, from the commits, not from a number ---
# subjects_of <tag-or-HEAD> : the commit subjects since the previous release tag
subjects_of() {
    local ref="$1" prev
    prev=$(git describe --tags --abbrev=0 --match 'v[0-9]*' "${ref}^" 2>/dev/null || true)
    if [ "$ref" = "HEAD" ]; then
        prev=$(git describe --tags --abbrev=0 --match 'v[0-9]*' HEAD 2>/dev/null || true)
        [ -n "$prev" ] && git log --format='%s' "$prev"..HEAD || git log --format='%s' -20
    else
        [ -n "$prev" ] && git log --format='%s' "$prev".."$ref" || git log --format='%s' -20 "$ref"
    fi
}

print_evidence() {
    echo "--- $TAG at $WHEN, window ${WINDOW_DAYS}d back to $(date -u -d "@$CUTOFF" +%Y-%m-%d\ %H:%M) ---"
    local n t d subj count
    for n in "${NEIGHBOURS[@]}"; do
        t="${n%%|*}"; d="${n##*|}"
        subj="$(subjects_of "$t")"
        count=$(printf '%s\n' "$subj" | grep -c . || true)
        echo
        echo "  $t  $d  ($count commits)"
        printf '%s\n' "$subj" | head -6 | cut -c1-110 | sed 's/^/      /'
        [ "$count" -gt 6 ] && echo "      ... and $((count - 6)) more"
    done
    subj="$(subjects_of "$TAG_REF")"
    count=$(printf '%s\n' "$subj" | grep -c . || true)
    echo
    echo "  $TAG  (this release, $count commits since the last tag)"
    printf '%s\n' "$subj" | head -6 | cut -c1-110 | sed 's/^/      /'
    [ "$count" -gt 6 ] && echo "      ... and $((count - 6)) more"
}

# --- his word clears any outcome, and is the record of it ---
if [ -n "${STREAK_OVERRIDE:-}" ]; then
    echo "OVERRIDDEN  $TAG — streak of $(( ${#NEIGHBOURS[@]} + 1 )) releases cleared by STREAK_OVERRIDE='${STREAK_OVERRIDE}'."
    echo "            (This value cannot be written by the releasing agent. If it appears"
    echo "             without Harald having set it, THAT is the finding.)"
    exit 0
fi

# --- no answer yet: ASK, with the evidence, and block nothing ---
if [ -z "$KIND" ]; then
    echo "ASK  $TAG is a patch release and ${#NEIGHBOURS[@]} other patch(es) of v${STREAK_SERIES}x landed inside ${WINDOW_DAYS} days."
    echo "     Tag numbers cannot say whether that is enhancement iteration or a fix series."
    echo "     Read what each release carried:"
    echo
    print_evidence
    cat <<'Q'

     THE QUESTION. Which is this?
       enhancement  new work, or independent fixes; no release here repairs a defect an earlier
                    release in this list tried to repair
       fix-series   two or more of these releases repair the SAME or a RELATED defect
       broke-next   the last fix broke something at the next level

     ANSWER FROM THE LIST ABOVE, NOT FROM MEMORY, and call again:
       patch-streak-gate.sh <tag> --kind enhancement|fix-series|broke-next \
           --because "<one line per release above, each naming its tag, saying what it was>"
     Only fix-series and broke-next stop the release. An enhancement answer is logged with its
     evidence so it can be checked against the commits afterwards.
Q
    exit 4
fi

# --- an answer must carry the evidence: every neighbouring release named ---
MISSING=()
for n in "${NEIGHBOURS[@]}"; do
    t="${n%%|*}"
    case "$BECAUSE" in *"$t"*) ;; *) MISSING+=("$t") ;; esac
done
if [ "${#MISSING[@]}" -gt 0 ]; then
    echo "REFUSED  the answer '$KIND' does not name: ${MISSING[*]}"
    echo "         --because must say, per release in the list, what it was. Run without --kind to see them."
    exit 3
fi

# --- log it, so a false answer sits next to the commits that contradict it ---
mkdir -p "$(dirname "$LOG")"
{
    printf '%s\t%s\t%s\t%s\t%s\n' "$(date -u +%Y-%m-%dT%H:%M:%SZ)" "$TAG" "$KIND" \
        "$(printf '%s' "${NEIGHBOURS[*]}" | tr '|' ' ' | tr '\n' ' ')" "$(printf '%s' "$BECAUSE" | tr '\n\t' '  ')"
} >> "$LOG"

if [ "$KIND" = "enhancement" ]; then
    echo "OK  $TAG answered ENHANCEMENT with evidence for ${#NEIGHBOURS[@]} release(s); logged to $LOG."
    exit 0
fi

echo "STREAK  $TAG answered '$KIND': the original alarm."
cat <<'WHY'

        A fix whose failure differs from the last is moving the defect, not fixing it.
        THE ACTION IS NOT ANOTHER FIX. Run the architect seat as a watch-diff over the WHOLE
        run, not the last patch, and ask: design fix, or bandage? Then put it in front of
        Harald. His word, recorded as STREAK_OVERRIDE=<his words> on a re-run, clears it.
WHY
exit 1
