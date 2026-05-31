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

# Category sub-roots under the tab root, one branch per type so the tree lays out cleanly.
# (Kept in sync with APTrackerRegistry on the Java side.)
CATEGORY_KILLS = f"{TRACKER_NAMESPACE}:category/kills"
CATEGORY_ENTITY_UNLOCKS = f"{TRACKER_NAMESPACE}:category/entity_unlocks"
CATEGORY_STRUCTURE_UNLOCKS = f"{TRACKER_NAMESPACE}:category/structure_unlocks"

# Which category an active tracker id belongs to (by id prefix).
CATEGORY_BY_KIND = {
    KIND_KILL: CATEGORY_KILLS,
    KIND_BOSS: CATEGORY_KILLS,
    KIND_UNLOCK_MOB: CATEGORY_ENTITY_UNLOCKS,
    KIND_UNLOCK_STRUCTURE: CATEGORY_STRUCTURE_UNLOCKS,
}


def _slug(game_id: str) -> str:
    """The path part of a game_id (``minecraft:zombie`` -> ``zombie``)."""
    return game_id.split(":", 1)[-1]


def tracker_id(kind: str, game_id: str) -> str:
    """Stable advancement id for a tracker, e.g. ``aem:kill/zombie``.

    ``game_id`` is the entity/structure game_id; the namespace is stripped so the path mirrors
    the vanilla id without colliding with it.
    """
    return f"{TRACKER_NAMESPACE}:{kind}/{_slug(game_id)}"


def build_trackers_export(world) -> dict:
    """Active-only ``{tracker_advancement_id: descriptor}`` for ``slot_data``.

    Descriptors:
      kill/boss   -> {"kind": "kill"|"boss", "location_name": <AP loc name>, "location_id": <id>}
      unlock      -> {"kind": "unlock", "item_id": <received item id>}
    """
    trackers: dict[str, dict] = {}

    # Kill / boss checks (locations actually created this seed).
    for loc_name, loc in world._get_active_locations().items():
        if loc.category == MCLocationCategory.MOB_KILL:
            trackers[tracker_id(KIND_KILL, loc.game_id)] = {
                "kind": KIND_KILL,
                "location_name": loc_name,
                "location_id": loc.id,
            }
        elif loc.category == MCLocationCategory.BOSS_KILL:
            trackers[tracker_id(KIND_BOSS, loc.game_id)] = {
                "kind": KIND_BOSS,
                "location_name": loc_name,
                "location_id": loc.id,
            }

    # Mob spawn unlocks (items the slot receives) — only the locked categories.
    locked_categories = set(world.options.mob_spawn_lock_category.value)
    for mob_data in MOBS_ALL.values():
        if mob_data.category in locked_categories:
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

    return trackers
