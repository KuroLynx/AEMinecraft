"""Filler & trap item effects exported to the Fabric mod via slot_data.

Each filler item grants concrete Minecraft items; each trap triggers a mod-side effect. The mod
applies these exactly once per received copy — it tracks a persisted high-water mark, since
Archipelago replays the whole item history on every reconnect. Names must match items.csv.
"""
from __future__ import annotations

# Filler item name -> (Minecraft item id, stack count) granted per received copy.
FILLER_GRANTS: dict[str, tuple[str, int]] = {
    "32 Breads": ("minecraft:bread", 32),
    "16 Beef": ("minecraft:beef", 16),
    "8 Golden Carrot": ("minecraft:golden_carrot", 8),
    "64 Torches": ("minecraft:torch", 64),
    "2 Ender Pearls": ("minecraft:ender_pearl", 2),
    "4 TNT": ("minecraft:tnt", 4),
    "32 Firework Rockets": ("minecraft:firework_rocket", 32),
    "Name Tag": ("minecraft:name_tag", 1),
    "64 Cooked Salmon": ("minecraft:cooked_salmon", 64),
    "16 Ink Sacs": ("minecraft:ink_sac", 16),
    "A Map": ("minecraft:map", 1),
}

# Filler that rolls the mod's "random_bullshit" loot table (a curated pool of non-useful items, so it
# can't grant anything that completes an out-of-logic advancement). Value = how many times to roll it.
RANDOM_FILLER: dict[str, int] = {
    "Random Bullshit": 5,
}

# Trap item name -> effect key handled by the mod's TrapEffects dispatcher.
TRAP_EFFECTS: dict[str, str] = {
    "Trap: Primed TNT": "primed_tnt",
    "Trap: Aww Man !": "aww_man",
    "Trap: Inventory Shuffle": "inventory_shuffle",
    "Trap: Slippery Fingers": "slippery_fingers",
    'Trap: "Dé à Coudre"': "thimble",
    'Trap: "POV: Paris Games Week"': "paris_games_week",
    "Trap: Item Fear": "item_fear",
}


def build_filler_export(item_name_to_id: dict[str, int]) -> dict[str, dict]:
    """``{item_id: {"item": mc_id, "count": n}}`` (or ``{"random": n}``) for each filler item."""
    export: dict[str, dict] = {}
    for name, (mc_id, count) in FILLER_GRANTS.items():
        item_id = item_name_to_id.get(name)
        if item_id is not None:
            export[str(item_id)] = {"item": mc_id, "count": count}
    for name, stacks in RANDOM_FILLER.items():
        item_id = item_name_to_id.get(name)
        if item_id is not None:
            export[str(item_id)] = {"random": stacks}
    return export


def build_trap_export(item_name_to_id: dict[str, int]) -> dict[str, str]:
    """``{item_id: effect_key}`` for each trap item."""
    export: dict[str, str] = {}
    for name, key in TRAP_EFFECTS.items():
        item_id = item_name_to_id.get(name)
        if item_id is not None:
            export[str(item_id)] = key
    return export
