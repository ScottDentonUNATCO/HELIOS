#!/usr/bin/env python3
"""
Minimal Maven dependency resolver for the OMNI manual APK build.

Resolves the transitive closure of the app's Gradle dependencies WITHOUT Gradle,
downloading AARs/JARs from Google's Maven repo and Maven Central.

Usage:
    python3 resolve_deps.py --out <repo>/apk-build/deps

Writes a manifest file <out>/MANIFEST.tsv with one line per artifact:
    groupId  artifactId  version  packaging  repo  local_path

Roots and versions mirror omni/gradle/libs.versions.toml.
"""
import argparse
import os
import re
import sys
import urllib.request
import urllib.error
import xml.etree.ElementTree as ET

REPOS = [
    "https://dl.google.com/dl/android/maven2/",
    "https://repo1.maven.org/maven2/",
]

# (group, artifact) -> version. None version = managed by compose BOM (imported below).
ROOTS = [
    ("androidx.compose", "compose-bom", "2024.10.00", "pom"),
    ("androidx.compose.ui", "ui", None, None),
    ("androidx.compose.material3", "material3", None, None),
    ("androidx.activity", "activity-compose", "1.9.3", None),
    ("androidx.lifecycle", "lifecycle-viewmodel-compose", "2.8.6", None),
    ("androidx.security", "security-crypto", "1.1.0-alpha06", None),
    ("org.jetbrains.kotlinx", "kotlinx-coroutines-android", "1.9.0", None),
]

# Artifacts we already have locally (manual-build/deps) - do not download,
# but still walk their POMs for transitives we might lack.
LOCAL_SKIP_DOWNLOAD = {
    ("com.squareup.okhttp3", "okhttp"),
    ("com.squareup.okio", "okio-jvm"),
    ("com.squareup.okio", "okio"),
    ("org.jetbrains.kotlinx", "kotlinx-coroutines-core-jvm"),
    ("org.jetbrains.kotlinx", "kotlinx-coroutines-core"),
    ("org.jetbrains.kotlinx", "kotlinx-serialization-core-jvm"),
    ("org.jetbrains.kotlinx", "kotlinx-serialization-core"),
    ("org.jetbrains.kotlinx", "kotlinx-serialization-json-jvm"),
    ("org.jetbrains.kotlinx", "kotlinx-serialization-json"),
}

SKIP_SCOPES = {"test", "provided", "system"}
NS = {"m": "http://maven.apache.org/POM/4.0.0"}


def log(*a):
    print(*a, flush=True)


def fetch_bytes(url):
    req = urllib.request.Request(url, headers={"User-Agent": "omni-manual-build/1.0"})
    with urllib.request.urlopen(req, timeout=30) as r:
        return r.read()


def group_path(group):
    return group.replace(".", "/")


def artifact_base_url(group, artifact, version):
    """Return (repo_url, base) trying each repo; raise if not found anywhere."""
    rel = f"{group_path(group)}/{artifact}/{version}/"
    last_err = None
    for repo in REPOS:
        url = repo + rel + f"{artifact}-{version}.pom"
        try:
            fetch_bytes(url)
            return repo, repo + rel
        except urllib.error.HTTPError as e:
            last_err = e
            continue
        except Exception as e:
            last_err = e
            continue
    raise RuntimeError(f"POM not found for {group}:{artifact}:{version}: {last_err}")


class PomCache:
    def __init__(self):
        self.poms = {}          # (g,a,v) -> (repo, base, Element)
        self.managed = {}       # (g,a) -> version  (from BOM imports)
        self.props_cache = {}

    def get(self, group, artifact, version):
        key = (group, artifact, version)
        if key in self.poms:
            return self.poms[key]
        repo, base = artifact_base_url(group, artifact, version)
        pom_xml = fetch_bytes(base + f"{artifact}-{version}.pom")
        root = ET.fromstring(pom_xml)
        self.poms[key] = (repo, base, root)
        return self.poms[key]

    def effective(self, group, artifact, version):
        """Return dict with groupId, artifactId, version, packaging, properties,
        dependencies (raw list), depMgmt, parent coords."""
        repo, base, root = self.get(group, artifact, version)

        def txt(el, name):
            c = el.find(f"m:{name}", NS)
            if c is None:
                c = el.find(name)
            return c.text.strip() if c is not None and c.text else None

        # parent chain (nearest first)
        chain = [(group, artifact, version, root)]
        seen = set()
        cur = root
        while True:
            p = cur.find("m:parent", NS)
            if p is None:
                p = cur.find("parent")
            if p is None:
                break
            pg, pa, pv = txt(p, "groupId"), txt(p, "artifactId"), txt(p, "version")
            if not pg or not pa or not pv or (pg, pa, pv) in seen:
                break
            seen.add((pg, pa, pv))
            try:
                _, _, proot = self.get(pg, pa, pv)
            except RuntimeError as e:
                log(f"  WARN: parent POM unavailable {pg}:{pa}:{pv}: {e}")
                break
            chain.append((pg, pa, pv, proot))
            cur = proot

        # merged properties: parents first, child overrides
        props = {}
        for _, _, _, r in reversed(chain):
            pr = r.find("m:properties", NS)
            if pr is None:
                pr = r.find("properties")
            if pr is not None:
                for c in pr:
                    tag = c.tag.split("}")[-1]
                    props[tag] = (c.text or "").strip()

        def sub(s):
            if not s:
                return s
            def rep(m):
                k = m.group(1)
                if k == "project.version":
                    return eff_version
                if k == "project.groupId":
                    return eff_group
                return props.get(k, m.group(0))
            prev = None
            cur_s = s
            for _ in range(10):
                cur_s = re.sub(r"\$\{([^}]+)\}", rep, cur_s)
                if cur_s == prev:
                    break
                prev = cur_s
            return cur_s

        # effective group/version (child's own, else parent's)
        eff_group = txt(root, "groupId")
        eff_version = txt(root, "version")
        if not eff_group or not eff_version:
            for _, _, _, r in chain[1:]:
                if not eff_group:
                    eff_group = txt(r, "groupId")
                if not eff_version:
                    eff_version = txt(r, "version")
                if eff_group and eff_version:
                    break
        eff_group = sub(eff_group or group)
        eff_version = sub(eff_version or version)

        packaging = txt(root, "packaging") or "jar"
        packaging = sub(packaging)

        # dependencyManagement: parents first, child overrides; handle BOM imports
        dep_mgmt = {}
        for _, _, _, r in reversed(chain):
            dm = r.find("m:dependencyManagement", NS)
            if dm is None:
                dm = r.find("dependencyManagement")
            if dm is not None:
                deps_el = dm.find("m:dependencies", NS)
                if deps_el is None:
                    deps_el = dm.find("dependencies")
                if deps_el is not None:
                    for d in (deps_el.findall("m:dependency", NS) or deps_el.findall("dependency")):
                        dg, da = sub(txt(d, "groupId")), sub(txt(d, "artifactId"))
                        dv, ds, dt = sub(txt(d, "version")), txt(d, "scope"), txt(d, "type")
                        if ds == "import" and (dt or "pom") == "pom" and dg and da and dv:
                            # BOM import: merge its dependencyManagement
                            try:
                                _, _, bom_root = self.get(dg, da, dv)
                                bdm = bom_root.find("m:dependencyManagement", NS)
                                if bdm is None:
                                    bdm = bom_root.find("dependencyManagement")
                                if bdm is not None:
                                    bdeps = bdm.find("m:dependencies", NS)
                                    if bdeps is None:
                                        bdeps = bdm.find("dependencies")
                                    if bdeps is not None:
                                        for bd in (bdeps.findall("m:dependency", NS) or bdeps.findall("dependency")):
                                            bg, ba = txt(bd, "groupId"), txt(bd, "artifactId")
                                            bv = sub(txt(bd, "version"))
                                            if bg and ba and bv:
                                                dep_mgmt[(bg, ba)] = bv
                                                self.managed[(bg, ba)] = bv
                            except RuntimeError as e:
                                log(f"  WARN: BOM import failed {dg}:{da}:{dv}: {e}")
                        elif dg and da and dv:
                            dep_mgmt[(dg, da)] = dv

        # raw dependencies of this pom (not parents' — Maven doesn't inherit deps... it does inherit! fix: merge)
        raw_deps = []
        for _, _, _, r in reversed(chain):
            ds_el = r.find("m:dependencies", NS)
            if ds_el is None:
                ds_el = r.find("dependencies")
            if ds_el is not None:
                for d in (ds_el.findall("m:dependency", NS) or ds_el.findall("dependency")):
                    dg, da = sub(txt(d, "groupId")), sub(txt(d, "artifactId"))
                    dv = sub(txt(d, "version"))
                    ds = txt(d, "scope") or "compile"
                    dt = txt(d, "type") or "jar"
                    opt = (txt(d, "optional") or "false").lower() == "true"
                    excl = []
                    ex_el = d.find("m:exclusions", NS)
                    if ex_el is None:
                        ex_el = d.find("exclusions")
                    if ex_el is not None:
                        for e in (ex_el.findall("m:exclusion", NS) or ex_el.findall("exclusion")):
                            eg = e.find("m:groupId", NS)
                            if eg is None:
                                eg = e.find("groupId")
                            ea = e.find("m:artifactId", NS)
                            if ea is None:
                                ea = e.find("artifactId")
                            excl.append(((eg.text.strip() if eg is not None and eg.text else "*"),
                                         (ea.text.strip() if ea is not None and ea.text else "*")))
                    if dg and da:
                        # child overrides same (g,a) from parent
                        raw_deps = [x for x in raw_deps if not (x[0] == dg and x[1] == da)]
                        raw_deps.append((dg, da, dv, ds, dt, opt, excl))
        return {
            "group": eff_group, "artifact": artifact, "version": eff_version,
            "packaging": packaging, "props": props, "deps": raw_deps,
            "dep_mgmt": dep_mgmt, "repo": repo, "base": base,
        }


def resolve_range(group, artifact, spec, cache):
    """Resolve a Maven version range to a concrete version.
    Handles [x] (exact), [x,y], [x,), (,y] via maven-metadata max-in-range."""
    s = spec.strip()
    # Exact pinned version: [1.7.4]
    m = re.match(r"^\[([^\],\)]+)\]$", s)
    if m:
        return m.group(1).strip()
    m = re.match(r"^[\[\(]([^,]*),([^)\]]*)[\]\)]$", s)
    if not m:
        return spec  # not a range at all
    lo, hi = m.group(1).strip(), m.group(2).strip()
    lo_inc, hi_inc = spec.strip()[0] == "[", spec.strip()[-1] == "]"
    versions = []
    for repo in REPOS:
        url = repo + f"{group_path(group)}/{artifact}/maven-metadata.xml"
        try:
            xml = fetch_bytes(url)
            root = ET.fromstring(xml)
            for v in root.iter("version"):
                versions.append(v.text.strip())
            break
        except Exception:
            continue
    def key(v):
        return [int(x) if x.isdigit() else x for x in re.split(r"[.\-]", v)]
    cands = []
    for v in versions:
        if lo and (key(v) < key(lo) or (key(v) == key(lo) and not lo_inc)):
            continue
        if hi and (key(v) > key(hi) or (key(v) == key(hi) and not hi_inc)):
            continue
        cands.append(v)
    if not cands:
        raise RuntimeError(f"no version in range {spec} for {group}:{artifact}")
    cands.sort(key=key)
    return cands[-1]


def vkey(v):
    """Sortable key for Maven-ish versions. Numeric segments sort numerically;
    a missing/empty qualifier sorts before any qualifier (1.1.0 > 1.1.0-alpha06)."""
    out = []
    for p in re.split(r"[.\-]", v):
        if p.isdigit():
            out.append((0, int(p), ""))
            continue
        m = re.match(r"^(\d+)(.*)$", p)
        if m:
            out.append((0, int(m.group(1)), m.group(2) or ""))
        else:
            out.append((1, 0, p))
    return out


def main():
    from collections import deque
    ap = argparse.ArgumentParser()
    ap.add_argument("--out", required=True)
    args = ap.parse_args()
    out = os.path.abspath(os.path.expanduser(args.out))
    os.makedirs(out, exist_ok=True)

    cache = PomCache()
    # Seed: resolve the BOM first so managed versions are known.
    log("seeding BOM androidx.compose:compose-bom:2024.10.00 ...")
    bom_eff = cache.effective("androidx.compose", "compose-bom", "2024.10.00")
    cache.managed.update(bom_eff["dep_mgmt"])
    log(f"managed versions from BOM: {len(cache.managed)}")
    # Gradle `platform(bom)` semantics: managed versions are FORCED, even when a
    # transitive POM declares its own (hard) version. Also covers -android variants.
    forced = dict(cache.managed)

    def force_lookup(g, a):
        if (g, a) in forced:
            return forced[(g, a)]
        if a.endswith("-android"):
            b = a[: -len("-android")]
            if (g, b) in forced:
                return forced[(g, b)]
        return None

    def excluded(g, a, excl):
        for eg, ea in excl:
            if (eg == "*" or eg == g) and (ea == "*" or ea == a):
                return True
        return False

    sightings = {}      # (g,a) -> set of version strings seen
    node_excl = {}      # (g,a) -> union of exclusion sets from all incoming edges
    chosen = {}         # (g,a) -> winning version
    expanded = {}       # (g,a) -> version whose deps were expanded
    order = []          # first-win order (for stable output)

    def record(g, a, v):
        s = sightings.setdefault((g, a), set())
        if v in s:
            return False
        s.add(v)
        return True

    queue = deque()
    for g, a, v, _kind in ROOTS:
        fv = force_lookup(g, a)
        if v is None:
            v = fv or cache.managed.get((g, a))
            if not v:
                raise RuntimeError(f"no managed version for root {g}:{a}")
        elif fv:
            v = fv
        record(g, a, v)
        queue.append((g, a))

    while queue:
        g, a = queue.popleft()
        fv = force_lookup(g, a)
        vs = sightings.get((g, a), set())
        if not vs:
            continue
        v = fv if fv else max(vs, key=vkey)
        if expanded.get((g, a)) == v:
            continue
        excl = node_excl.get((g, a), frozenset())
        if excluded(g, a, excl):
            continue
        try:
            eff = cache.effective(g, a, v)
        except (RuntimeError, urllib.error.HTTPError, urllib.error.URLError) as e:
            log(f"  WARN: cannot read POM {g}:{a}:{v}: {e}")
            vs.discard(v)
            if vs:
                queue.append((g, a))  # retry with next-best version
            continue
        if (g, a) not in chosen:
            order.append((g, a))
        chosen[(g, a)] = v
        expanded[(g, a)] = v
        for dg, da, dv, ds, dt, opt, dexc in eff["deps"]:
            if ds in SKIP_SCOPES or opt or dt == "test-jar":
                continue
            if dv is None:
                dv = eff["dep_mgmt"].get((dg, da)) or cache.managed.get((dg, da))
            if dv is None:
                log(f"  WARN: no version for {dg}:{da} (via {g}:{a}:{v}); skipping")
                continue
            dfv = force_lookup(dg, da)
            sv = dfv or dv
            if "[" in sv or "(" in sv:
                try:
                    sv = resolve_range(dg, da, sv, cache)
                except (RuntimeError, urllib.error.HTTPError, urllib.error.URLError) as e:
                    log(f"  WARN: cannot resolve range {sv} for {dg}:{da}: {e}")
                    continue
            new_excl = excl | frozenset(dexc)
            prev_excl = node_excl.get((dg, da), frozenset())
            if new_excl - prev_excl:
                node_excl[(dg, da)] = prev_excl | new_excl
                # exclusions changed: may need re-expansion below; fall through
            if excluded(dg, da, node_excl.get((dg, da), frozenset())):
                continue
            is_new = record(dg, da, sv)
            # re-queue if: brand-new version sighted, or the winning version changed
            cur_win = dfv if dfv else max(sightings[(dg, da)], key=vkey)
            if is_new and cur_win != expanded.get((dg, da)):
                queue.append((dg, da))
            elif not is_new and node_excl.get((dg, da), frozenset()) != prev_excl:
                queue.append((dg, da))

    log(f"resolved {len(chosen)} artifacts")

    # Download artifacts (skip pom-only and locally-provided).
    manifest = []
    for g, a in order:
        v = chosen[(g, a)]
        if (g, a) in LOCAL_SKIP_DOWNLOAD:
            manifest.append((g, a, v, "local", "-", "LOCAL(manual-build/deps)"))
            continue
        try:
            eff = cache.effective(g, a, v)
        except (RuntimeError, urllib.error.HTTPError, urllib.error.URLError) as e:
            log(f"  WARN: download phase cannot read {g}:{a}:{v}: {e}; skipping artifact")
            continue
        pkg = eff["packaging"]
        if pkg == "pom":
            manifest.append((g, a, v, "pom", eff["repo"], "-"))
            continue
        ext = "aar" if pkg == "aar" else "jar"
        url = eff["base"] + f"{a}-{v}.{ext}"
        dest_dir = os.path.join(out, group_path(g), a, v)
        os.makedirs(dest_dir, exist_ok=True)
        dest = os.path.join(dest_dir, f"{a}-{v}.{ext}")
        if not os.path.exists(dest) or os.path.getsize(dest) == 0:
            log(f"  GET {g}:{a}:{v} [{ext}]")
            data = None
            for try_ext, try_url in [(ext, url)] + (
                [("jar", eff["base"] + f"{a}-{v}.jar")] if ext == "aar" else []
            ):
                try:
                    data = fetch_bytes(try_url)
                    if try_ext != ext:
                        log(f"    aar 404, got jar instead")
                        ext = try_ext
                        dest = os.path.join(dest_dir, f"{a}-{v}.jar")
                    break
                except (urllib.error.HTTPError, urllib.error.URLError) as e:
                    log(f"    {try_ext} failed: {e}")
            if data is None:
                log(f"  WARN: no downloadable artifact for {g}:{a}:{v}; skipping")
                manifest.append((g, a, v, pkg, eff["repo"], "MISSING"))
                continue
            with open(dest, "wb") as f:
                f.write(data)
        else:
            log(f"  have {g}:{a}:{v} [{ext}]")
        manifest.append((g, a, v, pkg, eff["repo"], dest))

    with open(os.path.join(out, "MANIFEST.tsv"), "w") as f:
        for g, a, v, pkg, repo, path in manifest:
            f.write(f"{g}\t{a}\t{v}\t{pkg}\t{repo}\t{path}\n")
    log(f"wrote {out}/MANIFEST.tsv with {len(manifest)} entries")
    # also dump chosen versions for the report
    with open(os.path.join(out, "RESOLVED.txt"), "w") as f:
        for g, a in order:
            f.write(f"{g}:{a}:{chosen[(g,a)]}\n")


if __name__ == "__main__":
    main()
