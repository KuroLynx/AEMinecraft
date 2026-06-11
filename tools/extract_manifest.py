"""Extract a content pack's advancement manifest from a Minecraft jar, mod jar, or datapack.

Reads ``data/<namespace>/advancement[s]/**.json`` out of any source — the MC client jar (vanilla),
a mod jar (Twilight Forest), or a datapack (bacap), supplied as a ``.jar``/``.zip`` file or an
unpacked directory — and writes the normalised, lossless manifest the trigger compiler consumes.
This is the offline counterpart of the in-game Fabric ``/aem dump-advancements`` command; both
target the SAME schema, so the apworld treats vanilla, mods and datapacks uniformly.

Recipe "advancements" (``.../advancement[s]/recipes/...``) are skipped — they are not checks. Both
the modern ``advancement`` and the legacy ``advancements`` folder names are accepted, so older MC
versions / datapacks work too.

Manifest shape (sorted by id for stable diffs)::

    { "<namespace>:<path>": {
        "parent": "<id>" | null, "tab": "<first path segment>",
        "frame": "task" | "goal" | "challenge",
        "requirements": [[ "<criterion>", ... ], ...],          # MC CNF: AND of OR
        "criteria": { "<name>": {"trigger": "<id>", "conditions": { ... }} } }, ... }

Usage:
    python tools/extract_manifest.py             # vanilla -> packs/vanilla_26_1/manifest.json
    python tools/extract_manifest.py <src> <out>  # any mod jar / datapack zip / folder
"""
import json
import os
import re
import sys
import zipfile

REPO = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
# data/<namespace>/advancement or advancements/<rel>.json
_ADV_RE = re.compile(r"^data/([^/]+)/advancements?/(.+)\.json$")


def _default_jar() -> str:
    version = "26.1.2"
    props = os.path.join(REPO, "archipelago-euclesia-minecraft", "gradle.properties")
    if os.path.exists(props):
        with open(props, encoding="utf-8") as f:
            for line in f:
                if line.startswith("minecraft_version"):
                    version = line.split("=", 1)[1].strip()
                    break
    return os.path.join(os.path.expanduser("~"), ".gradle", "caches", "fabric-loom",
                        version, "minecraft-client.jar")


def _title(display: dict) -> str | None:
    """The advancement's display title. Datapacks (BACAP) put the literal name in ``translate``;
    vanilla uses a translation key there (unused — vanilla names come from its CSV)."""
    title = display.get("title")
    if isinstance(title, str):
        return title
    if isinstance(title, dict):
        return title.get("translate") or title.get("text")
    return None


def _record(data: dict, rel: str) -> dict:
    criteria = {
        name: {"trigger": body.get("trigger"), "conditions": body.get("conditions", {})}
        for name, body in data.get("criteria", {}).items()
    }
    display = data.get("display", {})
    return {
        "parent": data.get("parent"),
        "tab": rel.split("/", 1)[0],
        "frame": display.get("frame", "task"),
        "title": _title(display),
        "requirements": data.get("requirements", []),
        "criteria": criteria,
    }


def _entries(source: str):
    """Yield (archive_path, raw_bytes) for every file in ``source`` (zip/jar file or directory)."""
    if os.path.isdir(source):
        for root, _dirs, files in os.walk(source):
            for fn in files:
                full = os.path.join(root, fn)
                arc = os.path.relpath(full, source).replace(os.sep, "/")
                with open(full, "rb") as f:
                    yield arc, f.read()
    else:
        with zipfile.ZipFile(source) as zf:
            for name in zf.namelist():
                if not name.endswith("/"):
                    yield name, zf.read(name)


def extract(source: str) -> dict:
    manifest: dict = {}
    for arc, raw in _entries(source):
        match = _ADV_RE.match(arc)
        if not match:
            continue
        namespace, rel = match.group(1), match.group(2)
        if rel.startswith("recipes/"):
            continue
        manifest[f"{namespace}:{rel}"] = _record(json.loads(raw), rel)
    return dict(sorted(manifest.items()))


def main() -> int:
    source = sys.argv[1] if len(sys.argv) > 1 else _default_jar()
    out = sys.argv[2] if len(sys.argv) > 2 else os.path.join(
        REPO, "minecraft", "packs", "vanilla_26_1", "manifest.json")
    if not os.path.exists(source):
        print(f"source not found: {source}")
        print("usage: python tools/extract_manifest.py <jar|zip|dir> <out.json>")
        return 2
    manifest = extract(source)
    if not manifest:
        print(f"no advancements found under data/<ns>/advancement[s]/ in {source}")
        return 1
    with open(out, "w", encoding="utf-8") as f:
        json.dump(manifest, f, indent=2, ensure_ascii=False)
        f.write("\n")
    namespaces: dict[str, int] = {}
    for adv_id in manifest:
        ns = adv_id.split(":", 1)[0]
        namespaces[ns] = namespaces.get(ns, 0) + 1
    print(f"wrote {len(manifest)} advancements to {out}")
    print("by namespace:", json.dumps(namespaces, sort_keys=True))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
