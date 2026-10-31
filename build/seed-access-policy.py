#!/usr/bin/env python3
"""Allow places in the access policy of a throwaway resident (Sprint 28h, S13, D20).

A resident writes its access policy into its data folder the first time it starts, from the one that ships, and that file refuses a
place nobody listed. A script that starts a fresh resident and then asks it to load a project is doing what a person does when they
add a folder to the policy, so it does it the same way: it adds the folder to the "allow" list of the file, which the resident reads
again on the next call.

usage: seed-access-policy.py <data-dir> <place> [<place> ...]

Finds every access-policy.json under <data-dir> (the resident puts it one folder down) and adds each place that is not already there.
Exits 2 when there is no policy file yet, so a script that raced the resident's start-up fails loudly instead of loading into a refusal.
"""
import json
import pathlib
import sys


def main(argv):
    if len(argv) < 3:
        print(__doc__)
        return 2
    data = pathlib.Path(argv[1])
    places = argv[2:]
    files = sorted(data.rglob("access-policy.json"))
    if not files:
        print("seed-access-policy: no access-policy.json under %s yet" % data, file=sys.stderr)
        return 2
    for f in files:
        policy = json.loads(f.read_text())
        allow = policy.setdefault("allow", [])
        for place in places:
            if place not in allow:
                allow.append(place)
        f.write_text(json.dumps(policy, indent=2) + "\n")
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv))
