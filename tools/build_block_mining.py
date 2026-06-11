"""Build a content pack's block-mining table from a Minecraft jar / datapack.

How a block is broken decides the gate on items mined from it: which tool (if any — nether wart,
crops and glowstone break bare-handed) and which tool tier (a diamond pickaxe for obsidian, etc.).
That lives in the block tags ``mineable/{pickaxe,axe,shovel,hoe}`` and ``needs_{stone,iron,diamond}
_tool``; this inverts them to a per-block record the acquisition compiler consumes::

    "<block>": {"tool": "pickaxe"|"axe"|"shovel"|"hoe"|null,
                "needs": "stone"|"iron"|"diamond"|null}

A block absent from the table (or with ``tool: null``) needs no tool to break.

Usage:
    python tools/build_block_mining.py             # vanilla -> packs/vanilla_26_1/block_mining.json
    python tools/build_block_mining.py <jar|zip> <out>
"""
import json
import os
import re
import sys
import zipfile

REPO = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
_MINEABLE_RE = re.compile(r"/tags/block/mineable/(pickaxe|axe|shovel|hoe)\.json$")
_NEEDS_RE = re.compile(r"/tags/block/needs_(stone|iron|diamond)_tool\.json$")


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


def _members(data: bytes) -> list[str]:
    """Concrete block ids in a tag (nested ``#tag`` refs are ignored — the mineable/needs tags that
    matter list blocks directly)."""
    out = []
    for value in json.loads(data).get("values", []):
        entry = value if isinstance(value, str) else value.get("id", "")
        if entry and not entry.startswith("#"):
            out.append(entry.split(":", 1)[-1])
    return out


def build(source: str) -> dict:
    tools: dict[str, str] = {}
    needs: dict[str, str] = {}
    for name, data in _entries(source):
        if not name.endswith(".json"):
            continue
        tool_match = _MINEABLE_RE.search(name)
        needs_match = _NEEDS_RE.search(name)
        try:
            if tool_match:
                for block in _members(data):
                    tools[block] = tool_match.group(1)
            elif needs_match:
                for block in _members(data):
                    needs[block] = needs_match.group(1)
        except ValueError:
            continue

    table = {}
    for block in sorted(set(tools) | set(needs)):
        table[block] = {"tool": tools.get(block), "needs": needs.get(block)}
    return table


def main() -> int:
    source = sys.argv[1] if len(sys.argv) > 1 else _default_jar()
    out = sys.argv[2] if len(sys.argv) > 2 else os.path.join(
        REPO, "minecraft", "packs", "vanilla_26_1", "block_mining.json")
    if not os.path.exists(source):
        print(f"source not found: {source}")
        print("usage: python tools/build_block_mining.py <jar|zip|dir> <out.json>")
        return 2
    table = build(source)
    with open(out, "w", encoding="utf-8") as handle:
        json.dump(table, handle, indent=2, ensure_ascii=False, sort_keys=True)
        handle.write("\n")
    print(f"wrote {len(table)} blocks to {out}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
