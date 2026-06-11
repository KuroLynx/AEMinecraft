"""Extract a content pack's advancement manifest from a Minecraft jar.

Reads ``data/minecraft/advancement/**.json`` out of the MC client jar (loom cache) and writes a
normalised, lossless manifest the trigger compiler consumes. This is the offline counterpart of the
in-game Fabric ``/aem dump-advancements`` command (Milestone 3): both target the SAME schema, so the
apworld treats vanilla, mods and datapacks uniformly.

Recipe "advancements" (``minecraft:recipes/...``) are skipped — they are not randomised checks.

Manifest shape (sorted by id for stable diffs)::

    {
      "<id>": {
        "parent": "<id>" | null,
        "tab": "<first path segment>",
        "frame": "task" | "goal" | "challenge",
        "requirements": [[ "<criterion>", ... ], ...],   # MC CNF: outer AND of inner OR
        "criteria": { "<name>": {"trigger": "<id>", "conditions": { ... }} }
      }, ...
    }

Usage:
    python tools/extract_manifest.py                # auto-locate jar, write packs/vanilla_26_1/manifest.json
    python tools/extract_manifest.py <jar> <out>    # explicit
"""
import json
import os
import sys
import zipfile

REPO = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
ADV_PREFIX = "data/minecraft/advancement/"
RECIPE_PREFIX = "data/minecraft/advancement/recipes/"


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


def extract(jar_path: str) -> dict:
    manifest: dict = {}
    with zipfile.ZipFile(jar_path) as zf:
        for name in zf.namelist():
            if not name.startswith(ADV_PREFIX) or not name.endswith(".json"):
                continue
            if name.startswith(RECIPE_PREFIX):
                continue
            rel = name[len(ADV_PREFIX):-len(".json")]      # e.g. "adventure/bullseye"
            adv_id = f"minecraft:{rel}"
            data = json.loads(zf.read(name))
            criteria = {
                crit_name: {"trigger": body.get("trigger"), "conditions": body.get("conditions", {})}
                for crit_name, body in data.get("criteria", {}).items()
            }
            manifest[adv_id] = {
                "parent": data.get("parent"),
                "tab": rel.split("/", 1)[0],
                "frame": data.get("display", {}).get("frame", "task"),
                "requirements": data.get("requirements", []),
                "criteria": criteria,
            }
    return dict(sorted(manifest.items()))


def main() -> int:
    jar = sys.argv[1] if len(sys.argv) > 1 else _default_jar()
    out = sys.argv[2] if len(sys.argv) > 2 else os.path.join(
        REPO, "minecraft", "packs", "vanilla_26_1", "manifest.json")
    if not os.path.exists(jar):
        print(f"jar not found: {jar}\npass an explicit path: python tools/extract_manifest.py <jar> <out>")
        return 2
    manifest = extract(jar)
    with open(out, "w", encoding="utf-8") as f:
        json.dump(manifest, f, indent=2, ensure_ascii=False)
        f.write("\n")
    tabs: dict[str, int] = {}
    for rec in manifest.values():
        tabs[rec["tab"]] = tabs.get(rec["tab"], 0) + 1
    print(f"wrote {len(manifest)} advancements to {out}")
    print("by tab:", json.dumps(tabs, sort_keys=True))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
