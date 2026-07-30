"""Generate the station/container rows of ``minecraft_aem/content/knowledges.csv``.

Reads a pack's ``containers.json`` (dumped in-game by ``/aem dump containers`` or the title-menu dump
screen) and appends one Knowledge row per *gate group* — the set of blocks that should share a single
Knowledge item, so the 17 shulker boxes and the 9 copper chests don't mint 26 gates.

The dump reports facts (block id, kind, block entity class, recipe station); grouping is a design
decision, so it lives here rather than in the dump. Two rules, applied in order:

1.  **Variant prefix.** Strip a leading ``word_`` and adopt the remainder when that is itself a dumped
    block with the SAME block entity and the SAME recipe station: ``chipped_anvil`` -> ``anvil``,
    ``waxed_exposed_copper_chest`` -> ``exposed_copper_chest`` -> ``copper_chest``,
    ``soul_campfire`` -> ``campfire``, every dyed shulker box -> ``shulker_box``. The recipe-station
    check is what stops ``blast_furnace`` from collapsing into ``furnace``: they run different recipes
    and the logic has to tell them apart.
2.  **Same block entity.** Blocks that don't reduce but share a block entity class and recipe station
    are one thing in different woods (the 12 shelves); they group under the name their ids have in
    common (``acacia_shelf`` + ``oak_shelf`` + ... -> ``shelf``).

Rows are only ever APPENDED — knowledges.csv row order is the AP item id, so an existing row never
moves. Re-running after a fresh dump adds whatever is new and leaves everything else alone.

Usage:
    python tools/build_knowledges.py                 # apply to knowledges.csv
    python tools/build_knowledges.py --dry-run       # print what would be added
    python tools/build_knowledges.py --pack minecraft_26_1_2 --dump <path/to/containers.json>
"""
import argparse
import csv
import json
import os
import sys

REPO = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
CSV_PATH = os.path.join(REPO, "minecraft_aem", "content", "knowledges.csv")
DEFAULT_PACK = "minecraft_26_1_2"

# Blocks that hold an item without being anything you'd call storage. They are dumped (their block
# entity really is a Container) but a gate on them is noise, so they are left out of the generated
# rows; add them by hand if a seed ever wants them.
SKIP_BLOCKS = {
    "minecraft:jukebox",
    "minecraft:decorated_pot",
}


def load_dump(path: str) -> list[dict]:
    with open(path, encoding="utf-8") as f:
        return json.load(f)


def _signature(record: dict) -> tuple:
    """What makes two blocks 'the same thing' for gating: same block entity, same recipe station."""
    return (record.get("block_entity"), record.get("recipe_station"))


def resolve_groups(records: list[dict]) -> dict[str, list[str]]:
    """Map group name (a block id, or a synthesized common suffix) -> the block ids it covers."""
    by_id = {record["block"]: record for record in records}

    def reduce_once(block_id: str) -> str | None:
        namespace, _, path = block_id.partition(":")
        if "_" not in path:
            return None
        candidate = f"{namespace}:{path.split('_', 1)[1]}"
        if candidate not in by_id or _signature(by_id[candidate]) != _signature(by_id[block_id]):
            return None
        return candidate

    # Rule 1: walk each id down to its base variant.
    canonical: dict[str, str] = {}
    for block_id in by_id:
        seen = {block_id}
        current = block_id
        while (nxt := reduce_once(current)) is not None and nxt not in seen:
            seen.add(nxt)
            current = nxt
        canonical[block_id] = current

    # Rule 2: whatever is left over, group by signature — but only when a signature covers several
    # DIFFERENT bases, i.e. the same block entity in several flavours (the shelves).
    by_signature: dict[tuple, set[str]] = {}
    for block_id, base in canonical.items():
        record = by_id[block_id]
        if record.get("block_entity") is None:
            continue  # no block entity to key on; rule 1 is all we have
        by_signature.setdefault(_signature(record), set()).add(base)

    groups: dict[str, list[str]] = {}
    for block_id, base in canonical.items():
        bases = by_signature.get(_signature(by_id[block_id]), {base})
        name = common_suffix(sorted(bases)) if len(bases) > 1 else base
        groups.setdefault(name, []).append(block_id)
    return {name: sorted(blocks) for name, blocks in sorted(groups.items())}


def common_suffix(block_ids: list[str]) -> str:
    """The trailing ``_``-separated segments every id shares (``*_shelf`` -> ``minecraft:shelf``)."""
    namespace = block_ids[0].split(":", 1)[0]
    parts = [block_id.split(":", 1)[1].split("_") for block_id in block_ids]
    shared: list[str] = []
    for index in range(1, min(len(p) for p in parts) + 1):
        segment = {p[-index] for p in parts}
        if len(segment) != 1:
            break
        shared.insert(0, segment.pop())
    return f"{namespace}:{'_'.join(shared)}" if shared else block_ids[0]


def knowledge_name(group: str) -> str:
    """``minecraft:blast_furnace`` -> ``Blast Furnace`` (the bare name knowledges.csv stores)."""
    return group.split(":", 1)[-1].replace("_", " ").title()


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__,
                                     formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("--pack", default=DEFAULT_PACK)
    parser.add_argument("--dump", help="path to containers.json (default: the pack's own)")
    parser.add_argument("--dry-run", action="store_true")
    args = parser.parse_args()

    dump_path = args.dump or os.path.join(REPO, "minecraft_aem", "packs", args.pack, "containers.json")
    if not os.path.isfile(dump_path):
        print(f"no containers.json at {dump_path} — run /aem dump containers first", file=sys.stderr)
        return 1

    records = [r for r in load_dump(dump_path) if r["block"] not in SKIP_BLOCKS]
    groups = resolve_groups(records)
    kinds = {record["block"]: record["kind"] for record in records}

    with open(CSV_PATH, encoding="utf-8-sig", newline="") as f:
        existing_rows = list(csv.DictReader(f))
    existing = {row["name"] for row in existing_rows}

    added = []
    for group, blocks in groups.items():
        name = knowledge_name(group)
        if name in existing:
            continue
        # A group is a station if any block in it is: the dumped kind can differ across variants only
        # for oddities, and gating the group the stricter way is the safe reading.
        category = "station" if any(kinds[b] == "station" for b in blocks) else "container"
        added.append({"name": name, "category": category, "classification": "progression", "count": "1",
                      "blocks": blocks})

    added.sort(key=lambda row: (row["category"], row["name"]))
    for row in added:
        print(f"  + {row['name']:22} {row['category']:9} {len(row['blocks']):2d} block(s): "
              f"{', '.join(b.split(':')[1] for b in row['blocks'][:4])}"
              f"{' …' if len(row['blocks']) > 4 else ''}")
    print(f"{len(added)} new gate(s); {len(existing_rows)} row(s) already present")

    if args.dry_run or not added:
        return 0

    with open(CSV_PATH, "a", encoding="utf-8", newline="") as f:
        writer = csv.writer(f, lineterminator="\n")
        for row in added:
            writer.writerow([row["name"], row["category"], row["classification"], row["count"]])
    print(f"appended {len(added)} row(s) to {CSV_PATH}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
