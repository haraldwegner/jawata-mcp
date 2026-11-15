#!/usr/bin/env bash
# The seat files sealed in the engine (org.jawata.mcp/resources/seats, Sprint 28k Amendment 3, M10) are copies of jawata-studio's seats/
# at the commit named in build/seats.ref, until the studio reads its seats from the engine. This says whether the copies still equal that
# commit, and whether the studio has changed its seats since, so a seat edited in one place is not silently missing from the other.
#
# Usage: build/check-seats-match-studio.sh [<jawata-studio checkout>]   (default: jawata-studio beside the main checkout of this repository)
# Exit 0: the copies equal build/seats.ref and the studio's HEAD has the same seats. Exit 1: they differ, and each difference is named.
# Exit 2: the studio checkout or the commit cannot be read.
set -uo pipefail
here="$(cd "$(dirname "$0")/.." && pwd)"
# beside the main checkout, which a worktree finds through the git folder it shares
main="$(cd "$(git -C "$here" rev-parse --git-common-dir)/.." && pwd)"
studio="${1:-$(cd "$main/.." && pwd)/jawata-studio}"
ref="$(tr -d '[:space:]' < "$here/build/seats.ref")"
ours="$here/org.jawata.mcp/resources/seats"
git -C "$studio" cat-file -e "$ref^{commit}" 2>/dev/null || { echo "check-seats: $studio has no commit $ref" >&2; exit 2; }

differences=0
listed() { git -C "$studio" ls-tree --name-only "$1" seats/ | grep '\.md$' | sed 's|^seats/||' | sort; }
for name in $( (listed "$ref"; ls "$ours" | grep '\.md$') | sort -u); do
  if [ ! -f "$ours/$name" ]; then echo "missing in the engine: $name (the studio's $ref has it)"; differences=1; continue; fi
  if ! git -C "$studio" cat-file -e "$ref:seats/$name" 2>/dev/null; then echo "only in the engine: $name (the studio's $ref has no such seat)"; differences=1; continue; fi
  git -C "$studio" show "$ref:seats/$name" | cmp -s - "$ours/$name" || { echo "differs from the studio's $ref: $name"; differences=1; }
done
head="$(git -C "$studio" rev-parse HEAD)"
if [ "$head" != "$ref" ] && ! git -C "$studio" diff --quiet "$ref" "$head" -- seats/; then
  echo "the studio changed its seats after $ref (now $head): copy them again and move build/seats.ref"
  git -C "$studio" diff --stat "$ref" "$head" -- seats/ | tail -n +1
  differences=1
fi
[ "$differences" = 0 ] && echo "check-seats: the engine's $(ls "$ours" | grep -c '\.md$') seat files equal jawata-studio $ref, and the studio's seats have not changed since"
exit "$differences"
