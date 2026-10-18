#!/usr/bin/env python3
"""The hollow-wiring gate's check of its own load_project reply (REL44-45).

Usage: unwired-gate-load-check.py <reply.json>
Exit 0: the load succeeded. Exit 2: it did not, and the reason is printed.

It lived inline in unwired-gate.sh; it is a file so a test can drive the real rule.

The gate used to discard the engine's answer, and then printed it and stopped. The one
refusal that cost a release (4.4.9, about 25 minutes) was Maven dependency resolution after a
version bump: the new version's artifacts were built but never installed in the local Maven
repository, so the importer could not resolve them. The engine's sentence is right; the step
that cures it was in the repository's rules and not in the gate's message.
"""
import json
import sys

INSTALL_STEP = "mvn -B -f build/pom.xml install -DskipTests"


def main(path):
    try:
        raw = json.load(open(path))
        payload = json.loads(raw["result"]["content"][0]["text"])
    except Exception as e:                      # a malformed reply is a failed load too
        print("gate: RESULT=load-unreadable — could not parse the load_project reply:", e)
        print("gate: raw:", open(path).read()[:800])
        return 2
    if payload.get("success"):
        return 0
    err = payload.get("error", {})
    message = err.get("message") or ""
    print("gate: RESULT=load-failed — load_project refused, so nothing below could")
    print("gate: have swept anything. This is the CAUSE; 'no project loaded' is its symptom.")
    print("gate:   code:    %s" % err.get("code"))
    print("gate:   message: %s" % message)
    if err.get("hint"):
        print("gate:   hint:    %s" % err.get("hint"))
    if "dependency resolution" in message.lower():
        print("gate: A VERSION BUMP needs its artifacts installed in the local Maven repository")
        print("gate: before the importer can resolve them. Run, then re-run this gate:")
        print("gate:   %s" % INSTALL_STEP)
    return 2


if __name__ == "__main__":
    sys.exit(main(sys.argv[1]))
