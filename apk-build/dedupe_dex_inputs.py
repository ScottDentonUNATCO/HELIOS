#!/usr/bin/env python3
"""Drop fully-shadowed jars from a dex input list.

Reads candidate jar paths (one per line) from stdin. A candidate is dropped
iff every class it contains is also provided by another input that is NOT
being dropped. AAR classes.jars and compiled app jars are never candidates:
only the plain-JAR dep list passed via --candidates is eligible.

This handles stale artifacts absorbed into newer ones (e.g.
collection-ktx-1.2.0 fully shadowed by collection-jvm-1.4.4). Jars that fail
to read are kept (fail safe). Output: surviving paths, one per line.
"""
import sys
import zipfile
import argparse


def classes_of(path):
    try:
        with zipfile.ZipFile(path) as z:
            return {
                n
                for n in z.namelist()
                if n.endswith(".class") and not n.startswith("META-INF")
            }
    except Exception as e:
        print(f"  dedupe: unreadable, keeping: {path} ({e})", file=sys.stderr)
        return None


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--anchors", required=True,
                    help="file with jar paths that are never dropped (one per line)")
    ap.add_argument("--candidates", required=True,
                    help="file with droppable plain-jar paths (one per line)")
    args = ap.parse_args()

    with open(args.anchors) as f:
        anchors = [l.strip() for l in f if l.strip()]
    with open(args.candidates) as f:
        candidates = [l.strip() for l in f if l.strip()]

    cls = {}
    for p in anchors + candidates:
        c = classes_of(p)
        if c is not None:
            cls[p] = c

    alive = [p for p in candidates if p in cls]  # unreadable ones stay, handled below
    unreadable = [p for p in candidates if p not in cls]

    changed = True
    while changed:
        changed = False
        for p in list(alive):
            others = set()
            for q in anchors:
                if q in cls:
                    others |= cls[q]
            for q in alive:
                if q != p and q in cls:
                    others |= cls[q]
            if cls[p] <= others:
                label = p.split("/")[-1]
                if label == "classes.jar":
                    label = p.split("/")[-2] + "/classes.jar"
                print(f"  dedupe: dropping fully-shadowed {label}",
                      file=sys.stderr)
                alive.remove(p)
                changed = True
                break  # re-evaluate after each drop

    # Emit ONLY the surviving candidates — the caller already has the anchors.
    for p in alive + unreadable:
        print(p)


if __name__ == "__main__":
    main()
