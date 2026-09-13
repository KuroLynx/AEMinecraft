"""Tracker-tab export for the Fabric mod.

The mod ships a static datapack with one advancement per *possible* tracker (every mob kill,
boss kill, mob-spawn-unlock and structure-unlock) under the ``aem`` namespace. This module is
the single source of truth for the tracker advancement ids, used both by:

* ``tools/gen_tracker_advancements.py`` to emit that datapack, and
* :func:`build_trackers_export`, shipped in ``slot_data["trackers"]``, which lists the trackers
  that are *active this seed* and links each to its Archipelago meaning (a location for
  kills/bosses, a received item for unlocks).

The mod hides every tracker advancement that isn't in the export (the active set), and colours
the rest from this linkage — so the ids here must match the datapack ids exactly, which is why
both sides go through :func:`tracker_id`.
"""
from __future__ import annotations

from .data import (
    BASE_ID_ENTITY_UNLOCK,
    BASE_ID_STRUCT_UNLOCK,
    BOSS_KILL_PREFIX,
    ITEM_BIOME_FINDER,
    ITEMS,
    MCLocationCategory,
    MOBS_ALL,
    STRUCTURES,
)

TRACKER_NAMESPACE = "aem"
TRACKER_ROOT = f"{TRACKER_NAMESPACE}:archipelago"

# Tracker kinds (advancement-id path prefix).
KIND_KILL = "kill"
KIND_BOSS = "boss"
KIND_UNLOCK_MOB = "unlock_mob"
KIND_UNLOCK_STRUCTURE = "unlock_structure"
KIND_UNLOCK_KNOWLEDGE = "unlock_knowledge"

# Category roots — each is its own advancement-screen tab (a root with a background; see
# tools/gen_tracker_advancements.py). Kept in sync with APTrackerRegistry on the Java side.
CATEGORY_KILLS = f"{TRACKER_NAMESPACE}:category/kills"
CATEGORY_ENTITY_UNLOCKS = f"{TRACKER_NAMESPACE}:category/entity_unlocks"
CATEGORY_STRUCTURE_UNLOCKS = f"{TRACKER_NAMESPACE}:category/structure_unlocks"
CATEGORY_KNOWLEDGE = f"{TRACKER_NAMESPACE}:category/knowledge"

# Goal tiles on the main tab — native X/Y progress, rebuilt at runtime (RootAdvancementService).
GOAL_ADVANCEMENTS = f"{TRACKER_NAMESPACE}:goal/advancements"
GOAL_BOSSES = f"{TRACKER_NAMESPACE}:goal/bosses"

# Which category an active tracker id belongs to (by id prefix).
CATEGORY_BY_KIND = {
    KIND_KILL: CATEGORY_KILLS,
    KIND_BOSS: CATEGORY_KILLS,
    KIND_UNLOCK_MOB: CATEGORY_ENTITY_UNLOCKS,
    KIND_UNLOCK_STRUCTURE: CATEGORY_STRUCTURE_UNLOCKS,
    KIND_UNLOCK_KNOWLEDGE: CATEGORY_KNOWLEDGE,
}

# Knowledge / utility items tracked on the Knowledge tab — every "Knowledge: *", the progressive
# utilities (Material Handling, Villager Trust, Structure Finder) and the "Dimension Unlock: *"
# items. Derived from the item table so the set stays in sync with items.csv; the generator
# and the export both consume this list. Progressive items (count > 1) become one tile per level.
KNOWLEDGE_UTILITY_PREFIXES = ("Knowledge: ", "Progressive ", "Dimension Unlock: ")
# Finders gate like the others but their item names carry no tracked prefix, so list them by name.
KNOWLEDGE_UTILITY_EXTRAS = (ITEM_BIOME_FINDER,)
KNOWLEDGE_UTILITY_ITEMS = [
    name for name in ITEMS
    if name.startswith(KNOWLEDGE_UTILITY_PREFIXES) or name in KNOWLEDGE_UTILITY_EXTRAS
]


def _slug(game_id: str) -> str:
    """The path part of a game_id (``minecraft:zombie`` -> ``zombie``)."""
    return game_id.split(":", 1)[-1]


def tracker_id(kind: str, game_id: str) -> str:
    """Stable advancement id for a tracker, e.g. ``aem:kill/zombie``.

    ``game_id`` is the entity/structure game_id; the namespace is stripped so the path mirrors
    the vanilla id without colliding with it.
    """
    return f"{TRACKER_NAMESPACE}:{kind}/{_slug(game_id)}"


def goal_boss_tracker_id(game_id: str) -> str:
    """Stable id for a per-boss tile under the main-tab Bosses goal, e.g. ``aem:goal/bosses/ender_dragon``.

    These sit beneath the aggregate :data:`GOAL_BOSSES` tile and are linked (in the export) to that
    boss's kill location, so they colour exactly like the Kills-tab boss tiles."""
    return f"{GOAL_BOSSES}/{_slug(game_id)}"


def knowledge_slug(item_name: str) -> str:
    """Advancement-path slug for a knowledge/utility item name.

    ``Knowledge: Sword Handling`` -> ``sword_handling``; ``Progressive Villager Trust`` -> ``villager_trust``.
    """
    name = item_name
    for prefix in KNOWLEDGE_UTILITY_PREFIXES:
        if name.startswith(prefix):
            name = name[len(prefix):]
            break
    return name.lower().replace(" ", "_")


def knowledge_count(item_name: str) -> int:
    """How many progressive levels a knowledge/utility item has (1 for non-progressive items)."""
    return ITEMS[item_name].count


def knowledge_tracker_id(item_name: str, level: int) -> str:
    """Stable advancement id for one level of a knowledge/utility tracker, e.g.
    ``aem:unlock_knowledge/material_handling/3``. Non-progressive items have a single level ``1``."""
    return f"{TRACKER_NAMESPACE}:{KIND_UNLOCK_KNOWLEDGE}/{knowledge_slug(item_name)}/{level}"


def build_trackers_export(world) -> dict:
    """Active-only ``{tracker_advancement_id: descriptor}`` for ``slot_data``.

    Descriptors:
      kill/boss   -> {"kind": "kill"|"boss", "location_name": <AP loc name>, "location_id": <id>}
      unlock      -> {"kind": "unlock", "item_id": <received item id>}
    """
    trackers: dict[str, dict] = {}

    # Kill / boss checks (locations actually created this seed). Bosses additionally get a per-boss
    # tile under the main-tab Bosses goal — but only those the goal requires (boss_list), so the
    # aggregate goal/bosses tile is joined by one tile per boss you actually need to defeat.
    goal_boss_loc_names = {f"{BOSS_KILL_PREFIX}{name}" for name in world.selected_bosses}
    for loc_name, loc in world._get_active_locations().items():
        if loc.category == MCLocationCategory.MOB_KILL:
            trackers[tracker_id(KIND_KILL, loc.game_id)] = {
                "kind": KIND_KILL,
                "location_name": loc_name,
                "location_id": loc.id,
            }
        elif loc.category == MCLocationCategory.BOSS_KILL:
            # The Kills-tab boss tile only when kill_sanity is on: with killsanity off the Kills tab
            # is the killsanity tab, so bosses belong solely to the main tab's Bosses goal (below).
            # With no kill/boss trackers active the Kills category has none, so its tab stays hidden.
            if world.options.kill_sanity:
                trackers[tracker_id(KIND_BOSS, loc.game_id)] = {
                    "kind": KIND_BOSS,
                    "location_name": loc_name,
                    "location_id": loc.id,
                }
            if loc_name in goal_boss_loc_names:
                trackers[goal_boss_tracker_id(loc.game_id)] = {
                    "kind": KIND_BOSS,
                    "location_name": loc_name,
                    "location_id": loc.id,
                }

    # Mob spawn unlocks (items the slot receives) — only the mobs locked by mob_spawn_lock. The
    # static datapack has an unlock_mob tile per possible mob (grouped by category); activating just
    # the locked ones lights up exactly those tiles in their category blocks, like structure unlocks.
    for mob_name in world._get_locked_mobs():
        mob_data = MOBS_ALL[mob_name]
        trackers[tracker_id(KIND_UNLOCK_MOB, mob_data.game_id)] = {
            "kind": "unlock",
            "item_id": BASE_ID_ENTITY_UNLOCK + mob_data.id,
        }

    # Structure unlocks (items) — only structures locked by the structure_unlock option.
    for struct_name in world._get_locked_structures():
        struct_data = STRUCTURES[struct_name]
        trackers[tracker_id(KIND_UNLOCK_STRUCTURE, struct_data.game_id)] = {
            "kind": "unlock",
            "item_id": BASE_ID_STRUCT_UNLOCK + struct_data.id,
        }

    # Knowledge / utility items (received AP items) — green once received. Progressive items get one
    # tile per level: level N's tile turns green once N copies of the item are received. The start
    # dimension's unlock isn't in the pool (you spawn there), so skip it — its tile could never green.
    #
    # Gate on the items actually created this seed: options like villager_trust / structure_finder /
    # biome_finder drop their items from the pool entirely, and a tile for a never-granted item must
    # not appear (it could never turn green). item_name_to_id is the static full catalogue, so it
    # can't tell disabled options apart — the per-seed itempool (+ precollected for "start" modes) can.
    active_item_names = {
        item.name for item in world.multiworld.itempool if item.player == world.player
    }
    active_item_names.update(
        item.name for item in world.multiworld.precollected_items.get(world.player, [])
    )

    start_dimension_item = world._start_dimension_item()
    for item_name in KNOWLEDGE_UTILITY_ITEMS:
        if item_name == start_dimension_item or item_name not in active_item_names:
            continue
        item_id = world.item_name_to_id.get(item_name)
        if item_id is None:
            continue
        for level in range(1, knowledge_count(item_name) + 1):
            trackers[knowledge_tracker_id(item_name, level)] = {
                "kind": "unlock",
                "item_id": item_id,
                "count": level,
            }

    # How AP classifies each unlock item, as the network flags (progression 1, useful 2, trap 4), so
    # the tile can draw its name in AP's colour before the item ever arrives. Read off the placed
    # items rather than the item table: classification is decided per world at creation.
    flags_by_id: dict[int, int] = {}
    placed = [loc.item for loc in world.multiworld.get_locations() if loc.item]
    for item in placed + list(world.multiworld.precollected_items[world.player]):
        if item.player == world.player and item.code is not None:
            flags_by_id[item.code] = flags_by_id.get(item.code, 0) | item.flags
    for descriptor in trackers.values():
        if descriptor["kind"] == "unlock" and descriptor["item_id"] in flags_by_id:
            descriptor["flags"] = flags_by_id[descriptor["item_id"]]

    return trackers
