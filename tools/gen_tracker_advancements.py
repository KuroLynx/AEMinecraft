"""Generates the Archipelago tracker-tab datapack shipped inside the Fabric mod.

Emits one advancement per *possible* tracker (every mob kill / boss kill / mob-spawn-unlock /
structure-unlock) plus the ``aem:archipelago`` tab root, into the mod resources at
``archipelago-euclesia-minecraft/src/main/resources/data/aem/advancement/``. The seed-specific
subset is shown/hidden at runtime by the mod's visibility gate (slot_data["trackers"]).

Ids come from ``minecraft.trackers.tracker_id`` so they match the slot_data export exactly.
Icons are validated against the real item set: mob tiles use ``<slug>_spawn_egg`` (verified in
the MC client jar's en_us.json), structures use a hand-picked representative item.

Run from anywhere (puts the ArchipelagoClone checkout on sys.path, like tools/logic_selfcheck.py):
    python tools/gen_tracker_advancements.py
Override the client jar with env MC_CLIENT_JAR if the loom cache path differs.
"""
from __future__ import annotations

import json
import os
import re
import sys
import zipfile

# Mod resources output dir — resolved before any chdir.
REPO_ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
OUT_DIR = os.path.join(
    REPO_ROOT, "archipelago-euclesia-minecraft", "src", "main", "resources",
    "data", "aem", "advancement",
)

AP_ROOT = r"C:\Users\benja\PycharmProjects\ArchipelagoClone"
DEFAULT_JAR = os.path.expanduser(
    r"~/.gradle/caches/fabric-loom/26.1.2/minecraft-client.jar"
)

sys.path.insert(0, AP_ROOT)
os.chdir(AP_ROOT)

from worlds.minecraft_aem.data import MCEntityCategory, MOBS_ALL, STRUCTURES  # noqa: E402
from worlds.minecraft_aem.trackers import (  # noqa: E402
    CATEGORY_ENTITY_UNLOCKS,
    CATEGORY_KILLS,
    CATEGORY_KNOWLEDGE,
    CATEGORY_STRUCTURE_UNLOCKS,
    GOAL_ADVANCEMENTS,
    GOAL_BOSSES,
    KIND_BOSS,
    KIND_KILL,
    KIND_UNLOCK_MOB,
    KIND_UNLOCK_STRUCTURE,
    KNOWLEDGE_UTILITY_ITEMS,
    TRACKER_NAMESPACE,
    TRACKER_ROOT,
    goal_boss_tracker_id,
    knowledge_count,
    knowledge_tracker_id,
    tracker_id,
)

TAB_BACKGROUND = "minecraft:gui/advancements/backgrounds/adventure"
ROOT_ICON = "minecraft:nether_star"  # tab icon is overridden by the logo mixin; this is a fallback

# Tiles in a category are laid out as a grid of this many per row. Advancement screen layout is
# purely tree-topology based, so each row is a horizontal parent->child chain (depth = column) and
# successive rows branch off the category root (so they stack vertically). Inactive trackers this
# seed are simply hidden, leaving a blank cell — no dangling connector lines (lines are parent->child
# and a hidden parent draws none).
ROW_WIDTH = 8

# Mob tabs (Kills, Entity Unlocks) group tiles into contiguous per-category blocks, in this order,
# so a category is always laid out as its own grid among itself (passive, then hostile, neutral,
# bosses). Each block roots its own rows at the tab root, so categories never chain into one another
# — a hidden tile in one can't orphan another's (and bosses render even with kill_sanity off).
MOB_CATEGORY_ORDER = (
    MCEntityCategory.PASSIVE,
    MCEntityCategory.HOSTILE,
    MCEntityCategory.NEUTRAL,
    MCEntityCategory.BOSS,
)

# Mobs whose game_id has no matching <slug>_spawn_egg (variants, not their own entity type).
MOB_ICON_OVERRIDES = {
    "baby_zombie": "minecraft:zombie_spawn_egg",
}

# Representative valid item per structure (structures have no spawn egg / live render).
STRUCTURE_ICONS = {
    "ancient_city": "minecraft:sculk_catalyst",
    "bastion_remnant": "minecraft:polished_blackstone_bricks",
    "buried_treasure": "minecraft:heart_of_the_sea",
    "desert_pyramid": "minecraft:sandstone",
    "end_city": "minecraft:purpur_block",
    "fortress": "minecraft:nether_bricks",
    "igloo": "minecraft:snow_block",
    "jungle_pyramid": "minecraft:mossy_cobblestone",
    "mansion": "minecraft:dark_oak_planks",
    "mineshaft": "minecraft:rail",
    "mineshaft_mesa": "minecraft:powered_rail",
    "monument": "minecraft:prismarine",
    "nether_fossil": "minecraft:bone_block",
    "ocean_ruin_cold": "minecraft:trident",
    "ocean_ruin_warm": "minecraft:trident",
    "pillager_outpost": "minecraft:crossbow",
    "ruined_portal": "minecraft:obsidian",
    "ruined_portal_desert": "minecraft:obsidian",
    "ruined_portal_jungle": "minecraft:obsidian",
    "ruined_portal_mountain": "minecraft:obsidian",
    "ruined_portal_nether": "minecraft:obsidian",
    "ruined_portal_ocean": "minecraft:obsidian",
    "ruined_portal_swamp": "minecraft:obsidian",
    "shipwreck": "minecraft:oak_boat",
    "shipwreck_beached": "minecraft:oak_boat",
    "stronghold": "minecraft:end_portal_frame",
    "swamp_hut": "minecraft:cauldron",
    "trail_ruins": "minecraft:brush",
    "trial_chambers": "minecraft:trial_key",
    "village_desert": "minecraft:emerald",
    "village_plains": "minecraft:emerald",
    "village_savanna": "minecraft:emerald",
    "village_snowy": "minecraft:emerald",
    "village_taiga": "minecraft:emerald",
    "monster_room": "minecraft:spawner",
    "desert_well": "minecraft:sandstone_slab",
}
STRUCTURE_ICON_FALLBACK = "minecraft:chest"

# Representative item per knowledge/utility item name (the Knowledge tab tiles). Falls back to a book.
KNOWLEDGE_ICONS = {
    "Knowledge: Sword Handling": "minecraft:iron_sword",
    "Knowledge: Spear Handling": "minecraft:iron_spear",
    "Knowledge: Shovel Handling": "minecraft:iron_shovel",
    "Knowledge: Axe Handling": "minecraft:iron_axe",
    "Knowledge: Pickaxe Handling": "minecraft:iron_pickaxe",
    "Knowledge: Fishing": "minecraft:fishing_rod",
    "Knowledge: Hoe Handling": "minecraft:iron_hoe",
    "Knowledge: Shear Handling": "minecraft:shears",
    "Knowledge: Mace Handling": "minecraft:mace",
    "Knowledge: Trident Handling": "minecraft:trident",
    "Knowledge: Brush Handling": "minecraft:brush",
    "Knowledge: Pyromaniac": "minecraft:flint_and_steel",
    "Knowledge: Shield Handling": "minecraft:shield",
    "Knowledge: Armor Handling": "minecraft:iron_chestplate",
    "Knowledge: Brewing": "minecraft:brewing_stand",
    "Knowledge: Enchanting": "minecraft:enchanting_table",
    "Knowledge: Sharpshooter": "minecraft:bow",
    "Knowledge: Flying": "minecraft:elytra",
    "Progressive Material Handling": "minecraft:raw_iron",
    "Progressive Villager Trust": "minecraft:emerald",
    "Progressive Structure Finder": "minecraft:recovery_compass",
    "Biome Finder": "minecraft:compass",
    "Dimension Unlock: Overworld": "minecraft:grass_block",
    "Dimension Unlock: Nether": "minecraft:netherrack",
    "Dimension Unlock: The End": "minecraft:end_stone",
}
KNOWLEDGE_ICON_FALLBACK = "minecraft:book"

# Progressive Material Handling level -> (icon, material name). Each received copy raises the material
# tier the player can pick up (see minecraft/data.py MATERIAL_HANDLING_ITEMS), so each level's tile
# shows that tier's representative item.
MATERIAL_HANDLING_ITEM = "Progressive Material Handling"
MATERIAL_HANDLING_TIERS = {
    1: ("minecraft:stone", "Stone"),
    2: ("minecraft:copper_ingot", "Copper"),
    3: ("minecraft:iron_ingot", "Iron"),
    4: ("minecraft:gold_ingot", "Gold"),
    5: ("minecraft:diamond", "Diamond"),
    6: ("minecraft:netherite_ingot", "Netherite"),
}


def knowledge_icon(item_name: str) -> str:
    if item_name not in KNOWLEDGE_ICONS:
        print(f"  WARN: no icon mapping for knowledge item '{item_name}', using {KNOWLEDGE_ICON_FALLBACK}")
    return KNOWLEDGE_ICONS.get(item_name, KNOWLEDGE_ICON_FALLBACK)


def knowledge_level_tile(item_name: str, level: int, count: int) -> dict:
    """Tile (icon/title/description) for one level of a knowledge/utility item.

    Material Handling levels show the tier's ingot and material name; other progressive items repeat
    their icon with a level suffix; single-level items keep the plain name.
    """
    if item_name == MATERIAL_HANDLING_ITEM and level in MATERIAL_HANDLING_TIERS:
        icon, material = MATERIAL_HANDLING_TIERS[level]
        return {"icon": icon, "title": f"Material Handling: {material}",
                "description": f"Pick up {material.lower()}-tier materials"}
    if count > 1:
        return {"icon": knowledge_icon(item_name), "title": f"{item_name} {level}",
                "description": f"Unlock {item_name} (level {level})"}
    return {"icon": knowledge_icon(item_name), "title": item_name,
            "description": f"Unlock {item_name}"}


def load_spawn_egg_slugs(jar_path: str) -> set[str]:
    """The set of ``<x>`` for which ``minecraft:<x>_spawn_egg`` exists, from the jar lang file."""
    with zipfile.ZipFile(jar_path) as jar:
        lang = jar.read("assets/minecraft/lang/en_us.json").decode("utf-8")
    return set(re.findall(r"item\.minecraft\.([a-z0-9_]+)_spawn_egg", lang))


def _slug(game_id: str) -> str:
    return game_id.split(":", 1)[-1]


def mob_icon(game_id: str, spawn_eggs: set[str]) -> str:
    slug = _slug(game_id)
    if slug in spawn_eggs:
        return f"minecraft:{slug}_spawn_egg"
    if slug in MOB_ICON_OVERRIDES:
        return MOB_ICON_OVERRIDES[slug]
    print(f"  WARN: no spawn egg for '{slug}', using {MOB_ICON_OVERRIDES.get(slug, 'minecraft:egg')}")
    return "minecraft:egg"


def structure_icon(game_id: str) -> str:
    slug = _slug(game_id)
    if slug not in STRUCTURE_ICONS:
        print(f"  WARN: no icon mapping for structure '{slug}', using {STRUCTURE_ICON_FALLBACK}")
    return STRUCTURE_ICONS.get(slug, STRUCTURE_ICON_FALLBACK)


def tile(parent: str, icon: str, title: str, description: str, frame: str = "task") -> dict:
    """A trigger-less tracker advancement (never granted by gameplay; coloured by the overlay)."""
    return {
        "parent": parent,
        "criteria": {"never": {"trigger": "minecraft:impossible"}},
        "requirements": [["never"]],
        "display": {
            "icon": {"id": icon},
            "title": title,
            "description": description,
            "frame": frame,
            "show_toast": False,
            "announce_to_chat": False,
            "hidden": False,
        },
    }


def tab_root(icon: str, title: str, description: str) -> dict:
    """A parentless tracker advancement with a background — its own advancement-screen tab."""
    return {
        "criteria": {"never": {"trigger": "minecraft:impossible"}},
        "requirements": [["never"]],
        "display": {
            "icon": {"id": icon},
            "title": title,
            "description": description,
            "background": TAB_BACKGROUND,
            "show_toast": False,
            "announce_to_chat": False,
        },
    }


def write_grid(category_id: str, entries: list[dict]) -> None:
    """Write a category's tiles as rows of ROW_WIDTH.

    Each entry is {"id","icon","title","description","frame"}. Within a row, tile i parents tile
    i-1 (so the row extends right); the first tile of each row parents the category root (so rows
    stack down) — yielding a grid via tree topology.
    """
    rows = [entries[i:i + ROW_WIDTH] for i in range(0, len(entries), ROW_WIDTH)]
    write_rows(category_id, rows)


def write_rows(category_id: str, rows: list[list[dict]]) -> None:
    """Write a category as explicit rows. Each inner list is one horizontal parent->child chain
    (tile i parents tile i-1), and every row's first tile parents the category root, so the rows
    stack vertically. Use this (instead of write_grid) when row composition matters — e.g. to keep
    every level of a progressive item together on a single row so its levels always follow each
    other, instead of wrapping mid-item at the fixed ROW_WIDTH grid boundary.
    """
    for row in rows:
        for i, entry in enumerate(row):
            parent = category_id if i == 0 else row[i - 1]["id"]
            write(entry["id"], tile(parent, entry["icon"], entry["title"],
                                    entry["description"], entry.get("frame", "task")))


def rows_by_category(tiles_by_cat: dict[str, list[dict]]) -> list[list[dict]]:
    """Rows grouped into contiguous per-category blocks (MOB_CATEGORY_ORDER), each block chunked into
    rows of ROW_WIDTH. Since each row roots at the category root (see write_rows), the categories are
    laid out one grid-block per category and never chain into each other."""
    rows: list[list[dict]] = []
    for cat in MOB_CATEGORY_ORDER:
        tiles = tiles_by_cat.get(cat, [])
        rows += [tiles[i:i + ROW_WIDTH] for i in range(0, len(tiles), ROW_WIDTH)]
    return rows


def write(advancement_id: str, data: dict) -> None:
    # aem:kill/zombie -> <OUT_DIR>/kill/zombie.json
    rel = advancement_id.split(":", 1)[1]
    path = os.path.join(OUT_DIR, *rel.split("/")) + ".json"
    os.makedirs(os.path.dirname(path), exist_ok=True)
    with open(path, "w", encoding="utf-8") as f:
        json.dump(data, f, indent=2)
        f.write("\n")


def main() -> int:
    jar = os.environ.get("MC_CLIENT_JAR", DEFAULT_JAR)
    if not os.path.isfile(jar):
        print(f"ERROR: MC client jar not found: {jar}\nSet MC_CLIENT_JAR to override.")
        return 1
    spawn_eggs = load_spawn_egg_slugs(jar)
    print(f"loaded {len(spawn_eggs)} spawn eggs from {jar}")

    # Fresh output (drop stale tiles) but keep the dir.
    if os.path.isdir(OUT_DIR):
        import shutil
        shutil.rmtree(OUT_DIR)
    os.makedirs(OUT_DIR, exist_ok=True)

    count = 0

    # Main tab: a hub root (AP logo overrides the icon) with two goal tiles showing native X/Y
    # progress (rebuilt at runtime by RootAdvancementService).
    write(TRACKER_ROOT, tab_root(ROOT_ICON, "Archipelago", "Archipelago goal progress"))
    write(GOAL_ADVANCEMENTS, tile(TRACKER_ROOT, "minecraft:experience_bottle",
                                  "Advancements", "Goal advancements completed", frame="task"))
    write(GOAL_BOSSES, tile(TRACKER_ROOT, "minecraft:dragon_head",
                            "Bosses", "Goal bosses defeated", frame="goal"))
    count += 3

    # Each remaining category is its own tab (a parentless root with a background).
    write(CATEGORY_KILLS, tab_root("minecraft:iron_sword", "Kills", "Mobs & bosses to kill"))
    write(CATEGORY_ENTITY_UNLOCKS, tab_root("minecraft:egg", "Entity Unlocks", "Mob spawns to unlock"))
    write(CATEGORY_STRUCTURE_UNLOCKS, tab_root("minecraft:filled_map", "Structure Unlocks", "Structures to unlock"))
    write(CATEGORY_KNOWLEDGE, tab_root("minecraft:book", "Knowledge & Utilities", "Knowledge & utility items to unlock"))
    count += 4

    # Bucket tiles by mob category so each category lays out as its own contiguous grid block (see
    # MOB_CATEGORY_ORDER). Bosses land in their own block too, so they never parent a mob-kill tile:
    # mob kills are hidden when kill_sanity is off (or simply absent this seed), and an advancement
    # whose parent is hidden doesn't render — which used to leave the Kills tab showing only the one
    # boss that happened to land on a row boundary. Bosses always exist as checks, so they stand alone.
    kills_by_cat: dict[str, list[dict]] = {cat: [] for cat in MOB_CATEGORY_ORDER}
    unlocks_by_cat: dict[str, list[dict]] = {cat: [] for cat in MOB_CATEGORY_ORDER}
    # Per-boss tiles for the main-tab Bosses goal: one per possible boss, beneath the aggregate
    # goal/bosses tile. Emitted for every boss; the runtime export lists only the goal's bosses, so
    # the visibility gate shows just those (coloured by kill status like the Kills-tab boss tiles).
    boss_goals: list[dict] = []
    for name, mob in MOBS_ALL.items():
        icon = mob_icon(mob.game_id, spawn_eggs)
        if mob.category == MCEntityCategory.BOSS:
            kills_by_cat[MCEntityCategory.BOSS].append({"id": tracker_id(KIND_BOSS, mob.game_id),
                          "icon": icon, "title": name, "description": f"Defeat the {name}", "frame": "goal"})
            boss_goals.append({"id": goal_boss_tracker_id(mob.game_id), "icon": icon,
                               "title": name, "description": f"Defeat the {name}", "frame": "goal"})
        else:
            kills_by_cat[mob.category].append({"id": tracker_id(KIND_KILL, mob.game_id), "icon": icon,
                          "title": name, "description": f"Kill a {name}"})
        unlocks_by_cat[mob.category].append({"id": tracker_id(KIND_UNLOCK_MOB, mob.game_id), "icon": icon,
                               "title": name, "description": f"Unlock {name} spawns"})

    # STRUCTURES is keyed by game_id (slug); the pretty display name is struct.label.
    structure_unlocks = [
        {"id": tracker_id(KIND_UNLOCK_STRUCTURE, struct.game_id),
         "icon": structure_icon(struct.game_id), "title": struct.label,
         "description": f"Unlock {struct.label}"}
        for struct in STRUCTURES.values()
    ]

    # Knowledge tab layout: each progressive item (Material Handling, Villager Trust, Structure
    # Finder) gets its OWN row so its levels always follow each other left-to-right and never wrap
    # mid-item; the progressive rows are emitted first (so the progressives also follow one another),
    # then the single-level items pack into rows of ROW_WIDTH below them.
    progressive_rows: list[list[dict]] = []
    single_tiles: list[dict] = []
    for item_name in KNOWLEDGE_UTILITY_ITEMS:
        levels = knowledge_count(item_name)
        item_tiles = [
            {"id": knowledge_tracker_id(item_name, level),
             **knowledge_level_tile(item_name, level, levels)}
            for level in range(1, levels + 1)
        ]
        if levels > 1:
            progressive_rows.append(item_tiles)
        else:
            single_tiles.extend(item_tiles)
    knowledge_rows = progressive_rows + [
        single_tiles[i:i + ROW_WIDTH] for i in range(0, len(single_tiles), ROW_WIDTH)
    ]

    # Kills & Entity Unlocks tabs: one grid block per mob category (passive, hostile, neutral, bosses).
    write_rows(CATEGORY_KILLS, rows_by_category(kills_by_cat))
    write_rows(CATEGORY_ENTITY_UNLOCKS, rows_by_category(unlocks_by_cat))
    write_grid(CATEGORY_STRUCTURE_UNLOCKS, structure_unlocks)
    write_rows(CATEGORY_KNOWLEDGE, knowledge_rows)
    # Per-boss goal tiles each parent the Bosses goal DIRECTLY (one tile per row) rather than chaining
    # through each other: the runtime export activates only the goal's required bosses, and a chained
    # tile whose predecessor is hidden wouldn't render. Stacking them keeps every required boss visible.
    write_rows(GOAL_BOSSES, [[entry] for entry in boss_goals])
    count += (sum(len(t) for t in kills_by_cat.values())
              + sum(len(t) for t in unlocks_by_cat.values())
              + len(structure_unlocks)
              + sum(len(row) for row in knowledge_rows) + len(boss_goals))

    print(f"wrote {count} advancements to {OUT_DIR}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
