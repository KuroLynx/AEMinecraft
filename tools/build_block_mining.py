"""Build a content pack's block-mining table from a Minecraft jar / datapack.

Whether a mined item drops at all depends on the block's ``requiresCorrectToolForDrops`` flag and,
if set, its tool tier. That flag lives in block *code*, not datapack JSON, so it can't be read
offline — but in vanilla it is equivalent to "the block is pickaxe-mineable": stone / ores / metal
require a pickaxe, while shovel-, axe- and hoe-mineable blocks (soul sand, dirt, leaves, crops) and
untagged blocks (nether wart, glowstone) all drop bare-handed. So this records a pickaxe requirement
from the ``mineable/pickaxe`` tag and the tier from ``needs_{stone,iron,diamond}_tool``::

    "<block>": {"needs": "stone"|"iron"|"diamond"|null}   # present == requires a pickaxe

A block absent from the table breaks (and drops) with no tool.

A second requirement lives in the block's LOOT TABLE rather than a tag: some blocks only yield a
given item to a specific tool. Grass and ferns drop seeds bare-handed but themselves only to shears;
leaves and cobweb want shears or Silk Touch; a mushroom block yields itself only to Silk Touch.
Missing that made every such item free, which is the "Lichen Subscribe" bug — glow lichen, dead
bush, vine and grass all compiled to a bare region test. Recorded per dropped item, and only where a
tool is actually required::

    "<block>": {"needs": ..., "drops": {"<item>": ["shears"|"silk", ...]}}   # ANY of the tools

An item absent from ``drops`` has no tool requirement, so ``short_grass`` gates its own item behind
shears while the wheat seeds off the same block stay free.

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
_PICKAXE_RE = re.compile(r"/tags/block/mineable/pickaxe\.json$")
_NEEDS_RE = re.compile(r"/tags/block/needs_(stone|iron|diamond)_tool\.json$")
_LOOT_RE = re.compile(r"/loot_table/blocks/([a-z0-9_]+)\.json$")


def _tools(condition) -> set[str] | None:
    """The tools a loot condition demands, or ``None`` if it is not a tool gate at all.

    Only ``match_tool`` gates a drop on what you are holding; ``survives_explosion``,
    ``random_chance``, ``block_state_property`` and ``table_bonus`` say nothing about the tool and
    must not be mistaken for one. ``any_of`` is the shears-or-Silk-Touch shape."""
    if not isinstance(condition, dict):
        return None
    kind = condition.get("condition", "").split(":", 1)[-1]
    if kind == "any_of":
        found = set()
        for term in condition.get("terms", []):
            tools = _tools(term)
            if tools:
                found |= tools
        return found or None
    if kind != "match_tool":
        return None
    predicate = condition.get("predicate") or {}
    items = predicate.get("items")
    names = [items] if isinstance(items, str) else list(items or [])
    if any("shears" in str(n) for n in names):
        return {"shears"}
    enchants = (predicate.get("predicates") or {}).get("minecraft:enchantments") or []
    if any("silk_touch" in str(e.get("enchantments", "")) for e in enchants):
        return {"silk"}
    # A match_tool we cannot read still gates the drop on SOMETHING held; claiming "free" would be
    # the very bug this table exists to fix, so mark it unsatisfiable and let the caller drop the
    # route rather than hand the item out.
    return set()


def _walk_entries(node, inherited: list, out: dict) -> None:
    """Collect ``item -> tool requirement`` from a loot-table subtree.

    ``inherited`` is the conditions in scope: a pool's own conditions apply to every entry under it,
    and an ``alternatives`` child carries its siblings' fallbacks (grass drops seeds when the shears
    branch does not match). Recording the WEAKEST requirement seen for an item is what keeps a free
    route free."""
    if isinstance(node, list):
        for child in node:
            _walk_entries(child, inherited, out)
        return
    if not isinstance(node, dict):
        return
    conditions = inherited + list(node.get("conditions") or [])
    kind = node.get("type", "").split(":", 1)[-1]
    if kind in ("alternatives", "group", "sequence"):
        _walk_entries(node.get("children") or [], conditions, out)
        return
    if kind != "item":
        # dynamic / tag / loot_table entries name no concrete item
        _walk_entries(node.get("children") or [], conditions, out)
        return
    item = str(node.get("name", "")).split(":", 1)[-1]
    if not item:
        return
    required: set[str] | None = None
    for condition in conditions:
        tools = _tools(condition)
        if tools is None:
            continue                                  # not a tool gate; says nothing
        # Two tool gates on one entry must BOTH hold, so the tools that satisfy it are the ones
        # satisfying each — an intersection.
        required = set(tools) if required is None else (required & tools)
    if out.get(item, "unset") is None:
        return                                        # a free route already won
    if required is None:
        out[item] = None                              # this route needs no tool: the item is free
    else:
        # Another route for the same item is an alternative, so the tools that work are the union.
        out[item] = required if item not in out else (out[item] | required)


def _drops(data: bytes) -> dict:
    """Per-item tool requirements for one block loot table (only items that need a tool)."""
    table = json.loads(data)
    out: dict = {}
    for pool in table.get("pools") or []:
        _walk_entries(pool.get("entries") or [], list(pool.get("conditions") or []), out)
    return {item: sorted(tools) for item, tools in out.items() if tools}


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
    pickaxe: set[str] = set()
    needs: dict[str, str] = {}
    drops: dict[str, dict] = {}
    for name, data in _entries(source):
        if not name.endswith(".json"):
            continue
        needs_match = _NEEDS_RE.search(name)
        loot_match = _LOOT_RE.search(name)
        try:
            if _PICKAXE_RE.search(name):
                pickaxe.update(_members(data))
            elif needs_match:
                for block in _members(data):
                    needs[block] = needs_match.group(1)
            elif loot_match:
                needed = _drops(data)
                if needed:
                    drops[loot_match.group(1)] = needed
        except ValueError:
            continue

    # A block appears if it needs a pickaxe (tier from needs_*_tool) OR if some item it drops needs
    # a specific tool. Both are reasons a rule must ask for something; either alone is enough.
    out = {}
    for block in sorted(pickaxe | set(drops)):
        entry: dict = {"needs": needs.get(block)} if block in pickaxe else {}
        if block in drops:
            entry["drops"] = drops[block]
        out[block] = entry
    return out


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
