#!/usr/bin/env python3
"""
Extract AARs resolved by resolve_deps.py and merge their manifests.

Usage:
    python3 extract_aars.py --deps <repo>/apk-build/deps \
        --app-manifest <repo>/omni/app/src/main/AndroidManifest.xml \
        --out <repo>/apk-build/extract

Outputs under <out>/:
    <safe-name>/classes.jar, res/ (if any), AndroidManifest.xml, libs/*.jar (if any)
    EXTRACT.tsv            package, classes.jar, res dir, manifest per AAR
    merged-AndroidManifest.xml   app manifest + library contributions, package set
"""
import argparse
import os
import re
import shutil
import sys
import zipfile
import xml.etree.ElementTree as ET

ANDROID_NS = "http://schemas.android.com/apk/res/android"
ET.register_namespace("android", ANDROID_NS)
ET.register_namespace("tools", "http://schemas.android.com/tools")


def log(*a):
    print(*a, flush=True)


def safe(name):
    return re.sub(r"[^A-Za-z0-9_.-]", "_", name)


def parse_manifest(path):
    return ET.parse(path).getroot()


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--deps", required=True)
    ap.add_argument("--app-manifest", required=True)
    ap.add_argument("--out", required=True)
    ap.add_argument("--package", required=True)
    args = ap.parse_args()

    deps = os.path.abspath(os.path.expanduser(args.deps))
    out = os.path.abspath(os.path.expanduser(args.out))
    os.makedirs(out, exist_ok=True)

    manifest_tsv = os.path.join(deps, "MANIFEST.tsv")
    if not os.path.exists(manifest_tsv):
        sys.exit(f"MANIFEST.tsv not found in {deps}; run resolve_deps.py first")

    app_root = parse_manifest(args.app_manifest)
    app_pkg_el = app_root.find("application")
    if app_pkg_el is None:
        sys.exit("app manifest has no <application> element")

    merged_perms = set()
    for p in app_root.findall("uses-permission"):
        n = p.get(f"{{{ANDROID_NS}}}name")
        if n:
            merged_perms.add(n)

    rows = []
    with open(os.path.join(out, "EXTRACT.tsv"), "w") as tsv:
        for line in open(manifest_tsv):
            parts = line.rstrip("\n").split("\t")
            if len(parts) != 6:
                continue
            g, a, v, pkg, repo, path = parts
            if not path.endswith(".aar") or not os.path.exists(path):
                continue
            name = safe(f"{g}__{a}__{v}")
            dest = os.path.join(out, name)
            if os.path.exists(dest):
                shutil.rmtree(dest)
            os.makedirs(dest)
            with zipfile.ZipFile(path) as z:
                z.extractall(dest)
            cj = os.path.join(dest, "classes.jar")
            res = os.path.join(dest, "res") if os.path.isdir(os.path.join(dest, "res")) else ""
            man = os.path.join(dest, "AndroidManifest.xml")
            libs = []
            libs_dir = os.path.join(dest, "libs")
            if os.path.isdir(libs_dir):
                libs = sorted(os.path.join(libs_dir, f) for f in os.listdir(libs_dir) if f.endswith(".jar"))

            lib_pkg = ""
            if os.path.exists(man):
                try:
                    lroot = parse_manifest(man)
                    lib_pkg = lroot.get("package", "")
                    # merge permissions / features
                    for tag in ("uses-permission", "uses-feature", "permission"):
                        for el in lroot.findall(tag):
                            nm = el.get(f"{{{ANDROID_NS}}}name")
                            if tag == "uses-permission" and nm and nm not in merged_perms:
                                merged_perms.add(nm)
                                app_root.append(el)
                                log(f"  + permission {nm}  (from {a})")
                    # merge <application> children (providers, activities, meta-data...)
                    lapp = lroot.find("application")
                    if lapp is not None:
                        for child in list(lapp):
                            ctag = child.tag
                            cname = child.get(f"{{{ANDROID_NS}}}name", "?")
                            # avoid duplicates by android:name
                            dup = False
                            for existing in app_pkg_el.findall(ctag):
                                if existing.get(f"{{{ANDROID_NS}}}name") == cname and cname != "?":
                                    dup = True
                                    break
                            if not dup:
                                app_pkg_el.append(child)
                                log(f"  + application/{ctag} {cname}  (from {a})")
                except ET.ParseError as e:
                    log(f"  WARN: cannot parse {man}: {e}")
            else:
                log(f"  WARN: no AndroidManifest.xml in {a}")
                man = ""

            tsv.write(f"{lib_pkg}\t{cj if os.path.exists(cj) else ''}\t{res}\t{man}\t{';'.join(libs)}\n")
            rows.append((lib_pkg, cj, res, man, libs))
            log(f"extracted {g}:{a}:{v} -> {name}  pkg={lib_pkg} res={'yes' if res else 'no'}")

    # v8 ground truth: the working v8 APK's manifest contains the
    # InitializationProvider with ZERO Initializer meta-data entries.
    # The app never uses EmojiCompat / ProcessLifecycleOwner / ProfileInstaller,
    # and running EmojiCompatInitializer crashes the app before MainActivity
    # (v9/v10 both died at startup from this). Strip them all.
    for provider in app_pkg_el.findall("provider"):
        for md in list(provider.findall("meta-data")):
            if md.get(f"{{{ANDROID_NS}}}value") == "androidx.startup":
                log(f"  - removing startup Initializer {md.get(f'{{{ANDROID_NS}}}name')} (matches v8)")
                provider.remove(md)

    app_root.set("package", args.package)
    merged = os.path.join(out, "merged-AndroidManifest.xml")
    ET.ElementTree(app_root).write(merged, encoding="utf-8", xml_declaration=True)
    log(f"wrote merged manifest -> {merged}  ({len(rows)} AARs)")
    # dump merged manifest for the report
    log("---- merged manifest ----")
    log(open(merged).read()[:4000])


if __name__ == "__main__":
    main()
