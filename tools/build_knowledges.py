"""Generate the station/container rows of ``minecraft_aem/content/knowledges.csv``.

Reads a pack's ``containers.json`` (dumped in-game by ``/aem dump containers`` or the title-menu dump
screen) and appends one Knowledge row per *gate group* — the set of blocks that share a single
Knowledge item, so the 17 shulker boxes and the 9 copper chests don't mint 26 gates.

The grouping itself lives in the apworld (``content.registry.knowledge_groups``), because the world
needs the very same answer at load time to map blocks and recipe stations onto gates. This script is
just the offline half: it turns those groups into CSV rows a human can review in a diff.

Rows are only ever APPENDED — knowledges.csv row order is the AP item id, so an existing row never
moves. Re-running after a fresh dump adds whatever is new and leaves everything else alone.

Usage:
    python tools/build_knowledges.py                 # apply to knowledges.csv
    python tools/build_knowledges.py --dry-run       # print what would be added
    python tools/build_knowledges.py --pack minecraft_26_1_2
"""
import argparse
import csv
import os
import sys

AP_ROOT = r"C:\Users\benja\PycharmProjects\ArchipelagoClone"
sys.path.insert(0, AP_ROOT)

from worlds.minecraft_aem.content.registry import (  # noqa: E402
    gate_knowledge_name,
    knowledge_groups,
    load_containers,
)

REPO = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
CSV_PATH = os.path.join(REPO, "minecraft_aem", "content", "knowledges.csv")
DEFAULT_PACK = "minecraft_26_1_2"


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__,
                                     formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("--pack", default=DEFAULT_PACK)
    parser.add_argument("--dry-run", action="store_true")
    args = parser.parse_args()

    records = load_containers(args.pack)
    if not records:
        print(f"no containers.json in pack {args.pack} — run /aem dump containers first", file=sys.stderr)
        return 1

    groups = knowledge_groups(records)
    kinds = {record["block"]: record["kind"] for record in records}

    with open(CSV_PATH, encoding="utf-8-sig", newline="") as f:
        existing_rows = list(csv.DictReader(f))
    existing = {row["name"] for row in existing_rows}

    added = []
    for group, blocks in groups.items():
        name = gate_knowledge_name(group)
        if name in existing:
            continue  # already a row (including the pre-dump gates, via GATE_NAME_ALIASES)
        # A group is a station if any block in it is: variants rarely disagree, and gating the group the
        # stricter way is the safe reading.
        category = "station" if any(kinds[b] == "station" for b in blocks) else "container"
        added.append({"name": name, "category": category, "blocks": blocks})

    added.sort(key=lambda row: (row["category"], row["name"]))
    for row in added:
        print(f"  + {row['name']:22} {row['category']:9} {len(row['blocks']):2d} block(s): "
              f"{', '.join(b.split(':')[1] for b in row['blocks'][:4])}"
              f"{' ...' if len(row['blocks']) > 4 else ''}")
    print(f"{len(added)} new gate(s); {len(existing_rows)} row(s) already present")

    if args.dry_run or not added:
        return 0

    with open(CSV_PATH, "a", encoding="utf-8", newline="") as f:
        writer = csv.writer(f, lineterminator="\n")
        for row in added:
            writer.writerow([row["name"], row["category"], "progression", "1"])
    print(f"appended {len(added)} row(s) to {CSV_PATH}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
