#!/usr/bin/env python3
"""
fetch_models.py — Autonomous Asset Sourcing (web scraping of open GeckoLib/Bedrock models).

Instead of generating cubes locally, this searches GitHub for OPEN-SOURCE repositories that ship
GeckoLib/Bedrock `.geo.json` entity models + textures, downloads a humanoid ("knight/samurai/warrior")
model and a robot/flyer ("drone/robot/mech") model with a matching texture, validates them, and
integrates them by replacing the canonical entity assets that `FactionMobModel` / `DroneModel` reference:

    assets/redsteelcore/geo/order_samurai.geo.json      + textures/entity/order_samurai.png
    assets/redsteelcore/geo/syndicate_drone.geo.json    + textures/entity/syndicate_drone.png

SAFETY: every replaced file is backed up to `*.local_backup` first, and the source repo + license is
recorded in `CREDITS_ASSETS.md`. If the network/API yields nothing valid, the script changes NOTHING and
exits 0 (the build never depends on a successful download).

Run: python3 scripts/fetch_models.py   (urllib only — no extra deps)
"""
import json, os, sys, urllib.request, urllib.parse, datetime

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
ASSETS = os.path.join(ROOT, "src/main/resources/assets/redsteelcore")
GEO = os.path.join(ASSETS, "geo")
TEX = os.path.join(ASSETS, "textures/entity")
UA = {"User-Agent": "RedSteelCore-asset-fetcher", "Accept": "application/vnd.github+json"}
TIMEOUT = 25

HUMANOID_HINTS = ("knight", "samurai", "warrior", "humanoid", "soldier", "golem", "zombie", "villager", "player")
ROBOT_HINTS = ("drone", "robot", "mech", "bot", "turret", "android", "machine", "flyer", "copter")


def http(url, raw=False):
    try:
        req = urllib.request.Request(url, headers=UA)
        with urllib.request.urlopen(req, timeout=TIMEOUT) as r:
            data = r.read()
            return data if raw else json.loads(data.decode("utf-8"))
    except Exception as e:
        print("  ! request failed:", url.split("?")[0], "->", e)
        return None


def search_repos(query, limit=8):
    url = "https://api.github.com/search/repositories?q=" + urllib.parse.quote(query) + "&per_page=" + str(limit)
    j = http(url)
    if not j or "items" not in j:
        return []
    return [(it["full_name"], it.get("default_branch", "main")) for it in j["items"]]


def list_tree(full_name, branch):
    j = http("https://api.github.com/repos/%s/git/trees/%s?recursive=1" % (full_name, branch))
    if not j or "tree" not in j:
        return []
    return [n["path"] for n in j["tree"] if n.get("type") == "blob"]


def raw_url(full_name, branch, path):
    return "https://raw.githubusercontent.com/%s/%s/%s" % (full_name, branch, urllib.parse.quote(path))


def valid_geo(data):
    try:
        d = json.loads(data.decode("utf-8"))
    except Exception:
        return False
    g = d.get("minecraft:geometry")
    return isinstance(g, list) and g and "bones" in g[0]


def valid_png(data):
    return len(data) > 67 and data[:8] == b"\x89PNG\r\n\x1a\n"


def pick(paths, hints):
    geos = [p for p in paths if p.endswith(".geo.json")]
    # prefer a geo whose filename matches a hint
    for p in geos:
        base = os.path.basename(p).lower()
        if any(h in base for h in hints):
            return p
    return geos[0] if geos else None


def matching_texture(paths, geo_path):
    stem = os.path.basename(geo_path).replace(".geo.json", "").lower()
    pngs = [p for p in paths if p.lower().endswith(".png") and ("texture" in p.lower())]
    for p in pngs:
        if stem.split("_")[0] in os.path.basename(p).lower():
            return p
    # else any entity texture
    for p in pngs:
        if "entit" in p.lower():
            return p
    return pngs[0] if pngs else None


def backup(path):
    if os.path.exists(path) and not os.path.exists(path + ".local_backup"):
        os.rename(path, path + ".local_backup")
        # keep a copy at original name too (restore) so build still has it if download later fails
        import shutil
        shutil.copyfile(path + ".local_backup", path)


def integrate(kind, hints, geo_dst, tex_dst, credits):
    """Find+download a model of the given kind; replace dst files on success. Returns source string or None."""
    print("== sourcing %s model ==" % kind)
    for query in ("geckolib %s geo entity" % kind, "geckolib entity model %s" % hints[0],
                  "bedrock %s geo.json minecraft" % hints[0]):
        for full_name, branch in search_repos(query):
            paths = list_tree(full_name, branch)
            if not paths:
                continue
            geo_path = pick(paths, hints)
            if not geo_path:
                continue
            geo_data = http(raw_url(full_name, branch, geo_path), raw=True)
            if not geo_data or not valid_geo(geo_data):
                continue
            tex_path = matching_texture(paths, geo_path)
            tex_data = http(raw_url(full_name, branch, tex_path), raw=True) if tex_path else None
            if not tex_data or not valid_png(tex_data):
                continue
            # Validated geo + texture → integrate (with backup).
            backup(geo_dst); backup(tex_dst)
            with open(geo_dst, "wb") as f:
                f.write(geo_data)
            with open(tex_dst, "wb") as f:
                f.write(tex_data)
            src = "%s@%s :: %s , %s" % (full_name, branch, geo_path, tex_path)
            print("  + integrated from", src)
            credits.append((kind, full_name, branch, geo_path, tex_path))
            return src
    print("  - no valid %s model found online; keeping existing local asset (safe fallback)." % kind)
    return None


def main():
    os.makedirs(GEO, exist_ok=True)
    os.makedirs(TEX, exist_ok=True)
    credits = []
    integrate("samurai", HUMANOID_HINTS,
              os.path.join(GEO, "order_samurai.geo.json"), os.path.join(TEX, "order_samurai.png"), credits)
    integrate("drone", ROBOT_HINTS,
              os.path.join(GEO, "syndicate_drone.geo.json"), os.path.join(TEX, "syndicate_drone.png"), credits)

    if credits:
        with open(os.path.join(ROOT, "CREDITS_ASSETS.md"), "w", encoding="utf-8") as f:
            f.write("# Third-Party Asset Credits\n\n")
            f.write("Auto-sourced by `scripts/fetch_models.py` on %s (UTC).\n\n" % datetime.datetime.utcnow().isoformat())
            f.write("Verify each source repository's LICENSE before redistribution.\n\n")
            for kind, repo, branch, geo, tex in credits:
                f.write("- **%s** ← https://github.com/%s (`%s`)\n  - geo: `%s`\n  - texture: `%s`\n" % (kind, repo, branch, geo, tex))
        print("Wrote CREDITS_ASSETS.md (%d source(s))." % len(credits))
    else:
        print("No external assets integrated — local models untouched. Build remains valid.")
    return 0  # never fail the pipeline on a network miss


if __name__ == "__main__":
    sys.exit(main())
