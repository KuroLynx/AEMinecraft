"""Build a content pack's tag table (item + entity-type + block tags) from a Minecraft jar / datapack.

Advancement criteria reference tags, not just concrete ids — ``#minecraft:stone_tool_materials`` in
an ``inventory_changed``, ``#minecraft:raiders`` as a killed-entity type, ``#minecraft:ice`` as the
block stepped on (Smooth Operator). The trigger compiler expands those into an OR over the tag's
members, so it needs each tag flattened to its concrete ids. This reads
``data/<ns>/tags/{item,entity_type,block}/**.json`` (following nested ``#tag`` references) and writes
``packs/<pack>/tags.json``::

    { "item":        { "<ns>:<tag>": ["<ns>:<id>", ...], ... },
      "entity_type": { "<ns>:<tag>": ["<ns>:<id>", ...], ... },
      "block":       { "<ns>:<tag>": ["<ns>:<id>", ...], ... } }

Usage:
    python tools/build_tags.py                  # vanilla -> packs/vanilla_26_1/tags.json
    python tools/build_tags.py <jar|zip> <out>
"""
import json
import os
import re
import sys
import zipfile

REPO = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
# data/<ns>/tags/<registry>/<rel>.json — `items` is the legacy spelling of the `item` registry.
_TAG_RE = re.compile(r"^data/([^/]+)/tags/(item|items|entity_type|block|blocks)/(.+)\.json$")
_REGISTRY = {"item": "item", "items": "item", "entity_type": "entity_type",
             "block": "block", "blocks": "block"}


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


def _entries(source: str):
    if os.path.isdir(source):
        for root, _dirs, files in os.walk(source):
            for name in files:
                full = os.path.join(root, name)
                with open(full, "rb") as handle:
                    yield os.path.relpath(full, source).replace(os.sep, "/"), handle.read()
    else:
        with zipfile.ZipFile(source) as archive:
            for name in archive.namelist():
                if not name.endswith("/"):
                    yield name, archive.read(name)


def _load_raw(source: str) -> dict[str, dict[str, list]]:
    """registry -> {tag_id: raw values (ids and #nested-tag refs)}."""
    raw: dict[str, dict[str, list]] = {"item": {}, "entity_type": {}, "block": {}}
    for name, data in _entries(source):
        match = _TAG_RE.match(name)
        if not match:
            continue
        namespace, registry, rel = match.group(1), _REGISTRY[match.group(2)], match.group(3)
        try:
            raw[registry][f"{namespace}:{rel}"] = json.loads(data).get("values", [])
        except (ValueError, KeyError):
            continue
    return raw


def _resolve(tag_id: str, raw: dict[str, list], seen: set) -> list[str]:
    """Concrete member ids of a tag, following nested ``#tag`` references once each."""
    if tag_id in seen:
        return []
    seen.add(tag_id)
    members: list[str] = []
    for value in raw.get(tag_id, []):
        entry = value if isinstance(value, str) else value.get("id", "")
        if not entry:
            continue
        if entry.startswith("#"):
            members.extend(_resolve(entry[1:], raw, seen))
        else:
            members.append(entry)
    return members


def build(source: str) -> dict[str, dict[str, list]]:
    raw = _load_raw(source)
    table: dict[str, dict[str, list]] = {}
    for registry, tags in raw.items():
        table[registry] = {
            tag_id: sorted(set(_resolve(tag_id, tags, set()))) for tag_id in sorted(tags)
        }
    return table


def main() -> int:
    source = sys.argv[1] if len(sys.argv) > 1 else _default_jar()
    out = sys.argv[2] if len(sys.argv) > 2 else os.path.join(
        REPO, "minecraft", "packs", "vanilla_26_1", "tags.json")
    if not os.path.exists(source):
        print(f"source not found: {source}")
        print("usage: python tools/build_tags.py <jar|zip|dir> <out.json>")
        return 2
    table = build(source)
    with open(out, "w", encoding="utf-8") as handle:
        json.dump(table, handle, indent=2, ensure_ascii=False, sort_keys=True)
        handle.write("\n")
    print(f"wrote {sum(len(v) for v in table.values())} tags to {out}")
    print("  item:", len(table.get("item", {})),
          "| entity_type:", len(table.get("entity_type", {})),
          "| block:", len(table.get("block", {})))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
