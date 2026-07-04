"""Filler (buffs) & trap item effects exported to the Fabric mod via slot_data.

Filler no longer hands out Minecraft *items*: BACAP keys an advancement on obtaining almost every
item (its "collect all the items/blocks" and per-item advancements), so any granted item risked
completing an advancement out of logic. Instead each filler grants a temporary **buff** that the mod
reproduces *mechanically* (attribute modifiers + tick logic — see the mod's ``FillerBuffService``),
never applying a ``minecraft:`` ``MobEffect``, so it can't fire ``effects_changed`` and can't complete
any potion/effect advancement either. Traps still fire a mod-side effect.

The mod applies each received copy exactly once — it tracks a persisted high-water mark, since
Archipelago replays the whole item history on every reconnect. Names must match items.csv.
"""
from __future__ import annotations

# Base duration (seconds) granted per received copy. Duration STACKS: each copy adds this to the
# buff's remaining timer (the mod does the accumulation). Kept in slot_data so the apworld stays the
# source of truth for tuning.
BUFF_SECONDS_PER_COPY = 30

# Filler item name -> buff key handled by the mod's FillerBuffService. Every buff is reproduced via
# attributes/tick logic, so none of them can complete an advancement (see module docstring).
BUFF_FILLER: dict[str, str] = {
    # --- Classic potion analogues ---
    "Buff: Speed Boost"    : "speed",         # MOVEMENT_SPEED
    "Buff: Mining Haste"   : "haste",         # BLOCK_BREAK_SPEED
    "Buff: Strength"       : "strength",      # ATTACK_DAMAGE
    "Buff: Jump Boost"     : "jump_boost",    # JUMP_STRENGTH
    "Buff: Regeneration"   : "regeneration",  # tick heal
    "Buff: Absorption"     : "absorption",    # MAX_ABSORPTION + shield
    "Buff: Vitality"       : "health_boost",  # MAX_HEALTH
    "Buff: Lucky"          : "luck",          # LUCK
    "Buff: Toughness"      : "resistance",    # ARMOR / ARMOR_TOUGHNESS / KNOCKBACK_RESISTANCE
    # --- Fun extras attributes make cheap ---
    "Buff: Long Reach"     : "reach",         # BLOCK_/ENTITY_INTERACTION_RANGE
    "Buff: Sure-Footed"    : "step_assist",   # STEP_HEIGHT
    "Buff: Moon Boots"     : "low_gravity",   # GRAVITY
    "Buff: Full Belly"     : "saturation",    # tick feed
    "Buff: Miniaturize"    : "mini",          # SCALE down
    "Buff: Embiggen"       : "giant",         # SCALE up
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
    """``{item_id: {"buff": key, "seconds": n}}`` for each buff filler item."""
    export: dict[str, dict] = {}
    for name, key in BUFF_FILLER.items():
        item_id = item_name_to_id.get(name)
        if item_id is not None:
            export[str(item_id)] = {"buff": key, "seconds": BUFF_SECONDS_PER_COPY}
    return export


def build_trap_export(item_name_to_id: dict[str, int]) -> dict[str, str]:
    """``{item_id: effect_key}`` for each trap item."""
    export: dict[str, str] = {}
    for name, key in TRAP_EFFECTS.items():
        item_id = item_name_to_id.get(name)
        if item_id is not None:
            export[str(item_id)] = key
    return export
