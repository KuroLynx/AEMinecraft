"""Compile an advancement's Minecraft criteria into a logic-rule AST.

This is the engine that lets the apworld derive reachability for advancements nobody hand-authored
a rule for — the mass-import / mod / datapack case. It reads the normalised manifest record
(``{parent, requirements, criteria:{name:{trigger, conditions}}}`` — see tools/extract_manifest.py)
and produces an AST :class:`~..logic.ast.Rule`, reusing :class:`RuleHelper` (acquisition.py) as the
"how do I obtain / reach X" knowledge base.

It is deliberately *partial*: Minecraft has ~50 trigger types, many of them action-based
(``used_totem``, ``slept_in_bed``, ``target_hit``) whose real prerequisites are domain knowledge,
not present in the criterion. For anything it can't interpret with confidence, :meth:`compile`
returns ``None`` and the caller falls back (curated override, else parent-chain + region). Leaning
strict-and-fall-back never invents an over-permissive path that could soft-lock generation.

``requirements`` is Minecraft's CNF — a list (AND) of groups (OR) of criterion names — so it maps
straight onto ``and_`` / ``or_``.
"""
from __future__ import annotations

import json
import re
from importlib.resources import files

from ..data import (
    ADVANCEMENT_LOCATIONS,
    MOBS_ALL,
    MOBS_BREEDABLE,
    MOBS_LEASHABLE,
    MOBS_TAMEABLE,
    STRUCTURES,
)
from .acquisition import RuleHelper
from .ast import Rule, and_, or_
from .constants import (
    K_ARMOR,
    K_BREWING,
    MAT_IRON,
    REGION_END,
    REGION_NETHER,
    REGION_OVERWORLD,
)
from ..content.registry import base_pack, overlay_packs

# Triggers that imply a specific tool/block the criterion never names: hitting a target block is
# gated by crafting one (redstone + hay), brewing by a brewing stand, etc. Reaching the implied item
# is the meaningful gate, so the advancement inherits its acquisition logic.
_IMPLIED_ITEM = {
    "minecraft:target_hit": "minecraft:target",
    "minecraft:enchanted_item": "minecraft:enchanting_table",
    "minecraft:fishing_rod_hooked": "minecraft:fishing_rod",
}

# Items that carry a brewed potion (and so gate on the brewing chain, not loot).
_POTION_ITEMS = {
    "minecraft:potion", "minecraft:splash_potion",
    "minecraft:lingering_potion", "minecraft:tipped_arrow",
}

# Armor-trim material (a ``trim`` item predicate names it) -> the item that supplies it at a smithing
# table. The material is the region-binding requirement (netherite/quartz are Nether, the rest
# Overworld); the template and armor are region-neutral (templates appear in structures everywhere,
# armor is trivial), so they aren't modeled.
_TRIM_MATERIAL_ITEM = {
    "amethyst": "amethyst_shard", "copper": "copper_ingot", "diamond": "diamond",
    "emerald": "emerald", "gold": "gold_ingot", "iron": "iron_ingot", "lapis": "lapis_lazuli",
    "netherite": "netherite_ingot", "quartz": "quartz", "redstone": "redstone",
    "resin": "resin_brick",
}

_TAGS: dict | None = None
_BREWING: dict | None = None


def _pack_json(filename: str, pack: str | None = None) -> dict:
    pack = pack or base_pack()  # default to the discovered vanilla base pack
    root = __package__.rsplit(".", 1)[0]  # e.g. "worlds.minecraft_aem"
    with files(root).joinpath("packs", pack, filename).open(encoding="utf-8") as f:
        return json.load(f)


def _tags() -> dict:
    """Lazily-loaded item + entity-type tag table (tools/build_tags.py), keyed by tag id.

    Vanilla plus any optional pack (BACAP) merged in, so BACAP criteria that reference
    ``#blazeandcave:*`` tags (e.g. ``time_to_mine`` → ``#blazeandcave:pickaxes``) resolve instead of
    falling back to the parent chain. Tag namespaces don't collide (``minecraft:`` vs
    ``blazeandcave:``), so a per-registry dict merge is safe; absent pack tags are ignored."""
    global _TAGS
    if _TAGS is None:
        merged = _pack_json("tags.json")
        bacap = overlay_packs().get("blazeandcave")
        try:
            extra = _pack_json("tags.json", pack=bacap) if bacap else {}
        except (FileNotFoundError, OSError):
            extra = {}
        for registry, tags in extra.items():
            merged.setdefault(registry, {}).update(tags)
        _TAGS = merged
    return _TAGS


def _brewing() -> dict:
    """Lazily-loaded potion-type -> reagent items table (packs/.../brewing.json)."""
    global _BREWING
    if _BREWING is None:
        _BREWING = _pack_json("brewing.json")
    return _BREWING

# Minecraft dimension id -> our region name.
# Keyed by the bare dimension path; lookups go through _path so minecraft:the_end / the_end /
# (any namespace):the_end all resolve. BACAP writes these ids bare.
_DIMENSION_REGION = {
    "overworld": REGION_OVERWORLD,
    "the_nether": REGION_NETHER,
    "the_end": REGION_END,
}

# "Reaching / interacting with an entity" triggers: the criterion names an entity type and the
# rule is simply that the entity is reachable. (Killing it bare-handed is always possible; bosses
# carry their own curated kill rule, so these stay reachability-only here.)
_ENTITY_REACH_TRIGGERS = frozenset({
    "minecraft:entity_killed_player",
})

# Mobs that fire a projectile (for "deflect a projectile with a shield" — the criterion names no
# attacker, so any of these reachable, plus a shield, satisfies it). Skeleton is overworld-universal,
# so this is effectively "a shield + the Overworld".
_PROJECTILE_SHOOTERS = (
    "minecraft:skeleton", "minecraft:stray", "minecraft:bogged", "minecraft:pillager",
    "minecraft:blaze", "minecraft:ghast", "minecraft:witch", "minecraft:drowned",
    "minecraft:breeze",
)

# A few status effects with a non-brewing environmental source (the rest are gated on brewing in
# _effects_node). Each maps the effect id to a builder taking the compiler -> a Rule (or None).
_EFFECT_SOURCE = {
    "minecraft:levitation": lambda c: c._entity_gid("minecraft:shulker"),
    "minecraft:dolphins_grace": lambda c: c._entity_gid("minecraft:dolphin"),
    "minecraft:conduit_power": lambda c: c.h.acquire("minecraft:conduit"),
}

# Custom counter stats whose real prerequisite is simply obtaining an item (the count isn't a logic
# gate): eating cake slices needs access to cake (BACAP's "Must be your birthday").
_CUSTOM_STAT_ITEM = {
    "minecraft:eat_cake_slice": "minecraft:cake",
}

# Custom counter stats with no gameplay gate at all — pure elapsed-time counters: staying awake long
# enough for phantoms (BACAP's "Insomniac") is just waiting, reachable anywhere.
_CUSTOM_STAT_TRIVIAL = frozenset({"minecraft:time_since_rest", "minecraft:time_since_death"})

# Equipment slots an entity predicate can constrain (vanilla EquipmentSlot names). Read by
# _entity_equipment_node so "an entity wearing X" also requires being able to obtain X.
_EQUIPMENT_SLOTS = frozenset({"head", "chest", "legs", "feet", "body", "saddle",
                              "mainhand", "offhand"})

# Non-item natural blocks whose mere presence pins the dimension(s) you can be in to interact with one
# (entering / standing on / placing it). Each maps to the region(s) where that block exists — a block
# carries no acquirable item, and every advancement is placed in the Overworld region, so without this
# an `enter_block`/`stepping_on` on one would leak as Const(True), reachable from spawn (or, on a
# Nether start, reachable without the Overworld). Values are a tuple of regions (the block is in ONE of
# them → OR). Water exists in the Overworld AND the End (never the Nether); powder snow / berry bushes /
# dirt paths are Overworld-only; the portal/vine/soul-fire blocks pin the Nether or the End.
# Blocks whose dimension is not the whole story: something must have happened before one exists at
# all. Applied on top of _BLOCK_REGION by _block_region_node. Keyed by block path, value takes the
# RuleHelper so the gate is built lazily.
_BLOCK_EXTRA_GATE = {
    "end_gateway": lambda h: h.outer_end(),  # spawns only when the dragon dies
}

_BLOCK_REGION = {
    "end_portal": (REGION_END,),
    "end_gateway": (REGION_END,),
    "nether_portal": (REGION_NETHER,),
    "soul_fire": (REGION_NETHER,),
    "twisting_vines": (REGION_NETHER,),
    "twisting_vines_plant": (REGION_NETHER,),
    "weeping_vines": (REGION_NETHER,),
    "weeping_vines_plant": (REGION_NETHER,),
    "powder_snow": (REGION_OVERWORLD,),
    "sweet_berry_bush": (REGION_OVERWORLD,),
    "dirt_path": (REGION_OVERWORLD,),
    "water": (REGION_OVERWORLD, REGION_END),
    "bubble_column": (REGION_OVERWORLD, REGION_END),
    "water_cauldron": (REGION_OVERWORLD, REGION_END),
}


class TriggerCompiler:
    """Compiles a manifest record into a :class:`Rule`, or ``None`` if not confidently derivable."""

    def __init__(self, helper: RuleHelper, active_locations: frozenset | None = None):
        self.h = helper
        # Reverse lookups from Minecraft id -> our display-name key. The *_by_path variants are keyed
        # by the bare path so any namespace resolves (BACAP writes ids bare like "end_city" / "cow",
        # vanilla uses "minecraft:"): see _entity_name / _struct_name.
        self._entity_by_gid = {data.game_id: name for name, data in MOBS_ALL.items()}
        self._struct_by_gid = {data.game_id: name for name, data in STRUCTURES.items()}
        self._adv_loc_by_gid = {data.game_id: name for name, data in ADVANCEMENT_LOCATIONS.items()}
        self._entity_by_path = {self._path(gid): name for gid, name in self._entity_by_gid.items()}
        self._struct_by_path = {self._path(gid): name for gid, name in self._struct_by_gid.items()}
        self._item_tags = _tags().get("item", {})
        self._entity_tags = _tags().get("entity_type", {})
        self._block_tags = _tags().get("block", {})
        # Location names created this seed; a parent-chain rule must not reference a parent that was
        # filtered out (e.g. a challenge advancement when challenge_sanity is off) — that would make
        # AP's reachability sweep raise on an unknown location. None = don't restrict.
        self._active = active_locations

    # -- public -------------------------------------------------------------
    def compile(self, record: dict) -> Rule | None:
        """AST for ``record``'s requirements, or ``None`` if any AND-group is uninterpretable."""
        criteria = record.get("criteria", {})
        # Minecraft's default when `requirements` is absent/empty is "all criteria required" — each
        # criterion as its own AND-group (AdvancementRequirements.allOf). Datapacks (BACAP) usually
        # omit requirements and rely on this; the re-encoded vanilla jar always writes them out. Treat
        # empty-but-has-criteria as that default instead of "uninterpretable" (which dropped ~1159
        # BACAP advancements to the parent chain, ungating their real criteria).
        requirements = record.get("requirements") or [[name] for name in criteria]
        groups: list[Rule] = []
        for group in requirements:  # outer AND
            options: list[Rule] = []
            for crit_name in group:  # inner OR
                crit = criteria.get(crit_name)
                node = self._criterion(crit) if crit else None
                if node is not None:
                    options.append(node)
            # An OR-group with no interpretable option can't be guaranteed → fall back entirely.
            if not options:
                return None
            groups.append(or_(*options))
        if not groups:
            return None
        return and_(*groups)

    def parent_rule(self, record: dict) -> Rule | None:
        """Fallback logic: reach the parent advancement (datapack trees are parent-rooted). Returns
        ``None`` for roots / parents that aren't AP advancement locations, leaving the caller to
        default to always-reachable (region reachability still gates it elsewhere)."""
        parent_gid = record.get("parent")
        loc = self._adv_loc_by_gid.get(parent_gid) if parent_gid else None
        if loc is None or (self._active is not None and loc not in self._active):
            return None
        return self.h.reached(loc)

    # -- per-criterion dispatch --------------------------------------------
    def _criterion(self, crit: dict) -> Rule | None:
        trigger = crit.get("trigger")
        cond = crit.get("conditions") or {}
        # Minecraft treats an unnamespaced trigger as `minecraft:` (BACAP writes some criteria as
        # bare `consume_item` / `inventory_changed`), so normalise before dispatch.
        if isinstance(trigger, str) and ":" not in trigger:
            trigger = f"minecraft:{trigger}"

        if trigger in _ENTITY_REACH_TRIGGERS:
            return self._entity_killed_player_node(cond)
        if trigger == "minecraft:player_killed_entity":
            return self._player_killed_node(cond)
        if trigger == "minecraft:summoned_entity":
            # Summoning means *building* the entity (an Iron Golem from blocks + a carved pumpkin),
            # not merely encountering one — a naturally spawned village golem does not count. Gate on
            # the build recipe (RuleHelper.summon), OR over the entity type(s) the criterion pins.
            options = [self.h.summon(name) for name in self._entity_names(cond)]
            return or_(*options) if options else None
        if trigger == "minecraft:player_hurt_entity":
            return self._player_hurt_node(cond)
        if trigger == "minecraft:entity_hurt_player":
            return self._entity_hurt_player_node(cond)
        if trigger == "minecraft:killed_by_arrow":
            return self._killed_by_arrow_node(cond)
        if trigger == "minecraft:player_interacted_with_entity":
            # Right-click an entity with an item (lead a mob, feed it, …): need the item AND a valid
            # target entity. A concrete type pins it; an inverted predicate ("any entity except the
            # listed vehicles/non-mobs", e.g. Lead the Way!) is the "lead a mob" shape, so the real
            # gate is any LEASHABLE mob not excluded — leashability is game-derived (MOBS_LEASHABLE
            # from entities.json), more precise than the old "any mob". Fall back to the registry minus
            # the excluded set if (unexpectedly) no leashable mob survives — both fully data-driven.
            item = self._item_predicate(cond.get("item"))
            entity = self._entity_node(cond)
            if entity is None and cond.get("entity"):
                excluded = set(self._excluded_entity_names(cond))
                candidates = [n for n in MOBS_LEASHABLE if n not in excluded] \
                    or [n for n in MOBS_ALL if n not in excluded]
                entity = self._any_mob(candidates, self.h.entity)
            parts = [n for n in (item, entity) if n is not None]
            return and_(*parts) if parts else None
        if trigger == "minecraft:tame_animal":
            # The tamed species is pinned on the `entity` predicate as a concrete type or a #tag
            # (#blazeandcave:llamas); empty conditions mean "tame any tameable animal".
            names = self._entity_names(cond)
            if not names:
                species = self._species_from_components(cond)
                names = [species] if species else []
            options = [self.h.can_tame(n) for n in names if n in MOBS_TAMEABLE]
            if options:
                return or_(*options)
            return self._any_mob(MOBS_TAMEABLE, self.h.can_tame) if not cond else None
        if trigger == "minecraft:bred_animals":
            # The bred species is pinned on `child` (vanilla bred_all_animals) or directly on
            # `parent` / `partner` (BACAP's "breed two cows"); empty conditions mean "breed any".
            gid = self._predicate_value(cond.get("child"), "type")
            if not isinstance(gid, str):
                # `parent`/`partner` may be a bare dict or the entity_properties list form (frog /
                # sniffer / turtle, whose breeding yields a tadpole/egg, not a `child` entity).
                gid = self._predicate_value(cond.get("parent") or cond.get("partner"), "type")
            # `gid` may be a concrete species or a #tag (#blazeandcave:llamas → breed any llama). An
            # explicitly pinned species is gated via can_breed regardless of the `breedable` flag —
            # can_breed already models its food + reachability, and Two by Two pins frog/sniffer/turtle/
            # nautilus which aren't flagged breedable but whose breeding (incl. the Nether strider &
            # hoglin) is the real gate. The flag-derived MOBS_BREEDABLE is only for the "breed any" case.
            names = self._entity_names_from_type(gid)
            if names:
                return or_(*[self.h.can_breed(n) for n in names])
            return self._any_mob(MOBS_BREEDABLE, self.h.can_breed) if not cond else None
        if trigger == "minecraft:changed_dimension":
            region = _DIMENSION_REGION.get(self._path(cond.get("to")))
            # enter_dimension, not access_region: entering the dimension you START in means leaving
            # and coming back, which spawning there does not satisfy.
            return self.h.enter_dimension(region) if region else None
        if trigger == "minecraft:location":
            return self._location_node(cond)
        if trigger == "minecraft:inventory_changed":
            return self._inventory_node(cond)
        if trigger == "minecraft:consume_item":
            # "Eat any item" (empty conditions) → any food source; else resolve the specific item.
            return self.h.can_get_food() if not cond else self._item_predicate(cond.get("item"))
        if trigger in ("minecraft:using_item", "minecraft:shot_crossbow"):
            return self._used_item_node(cond)
        if trigger == "minecraft:placed_block":
            return self._placed_block_node(cond)
        if trigger == "minecraft:slide_down_block":
            # Slide down a block (e.g. honey) → you must be able to obtain that block. The block sits
            # on the `block` key (not `blocks`), so read every form via _blocks_in.
            return self._any_acquire(self._blocks_in(cond))
        if trigger == "minecraft:item_used_on_block":
            return self._used_on_block_node(cond)
        if trigger == "minecraft:filled_bucket":
            return self._filled_bucket_node(cond)
        if trigger == "minecraft:recipe_crafted":
            ingredients = cond.get("ingredients")
            if ingredients:
                return self._all_items(ingredients)
            return self._recipe_id_node(cond.get("recipe_id"))
        if trigger == "minecraft:construct_beacon":
            node = self.h.acquire("minecraft:beacon")  # nether star (Wither) + glass + obsidian
            if node is not None and self._min_level(cond.get("level")) >= 1:
                # A pyramid is required → also need a base material (any beacon-base block).
                node = self._all_req(node, self.h.can_get_beacon_base())
            return node
        if trigger == "minecraft:villager_trade":
            return self.h.can_trade_villager()
        if trigger == "minecraft:slept_in_bed":
            return self.h.access_region(REGION_OVERWORLD)  # a bed needs wool + planks (overworld)
        if trigger == "minecraft:used_totem":
            return self.h.acquire("minecraft:totem_of_undying")
        if trigger == "minecraft:player_generates_container_loot":
            return self._container_loot_node(cond)
        if trigger in ("minecraft:default_block_use", "minecraft:any_block_use",
                       "minecraft:enter_block"):
            # Use / stand in a block. A block that pins a dimension (an end gateway / nether portal /
            # Nether-only vine) gates on reaching that dimension — checked first, as that is the
            # authoritative gate for these (the acquisition table even mis-models twisting_vines as
            # Overworld-obtainable). Otherwise obtain it if craftable/obtainable; an Overworld-natural
            # block carries no gate.
            blocks = self._blocks_in(cond)
            node = self._block_region_node(blocks) or self._any_acquire(blocks)
            return node if node is not None else and_()
        if trigger in ("minecraft:item_durability_changed", "minecraft:player_sheared_equipment"):
            # Wear an item down / shear with one → obtain that item (shears for shearing).
            item = self._item_predicate(cond.get("item"))
            return item if item is not None else self.h.acquire("minecraft:shears")
        if trigger in ("minecraft:thrown_item_picked_up_by_player",
                       "minecraft:thrown_item_picked_up_by_entity"):
            # An item was tossed and picked up → obtain that item AND, whenever a specific entity is
            # pinned on `entity`, reach it: the picker-upper for *_by_entity (a piglin for Oh Shiny),
            # or the thrower for *_by_player (an allay for You've Got a Friend in Me, which pins no
            # item at all — the allay is the whole gate).
            item = self._item_predicate(cond.get("item"))
            entity = self._entity_node(cond)
            parts = [n for n in (item, entity) if n is not None]
            return and_(*parts) if parts else None
        if trigger == "minecraft:crafter_recipe_crafted":
            # Auto-craft via a Crafter → build a Crafter (its ingredients gate it further).
            return self.h.acquire("minecraft:crafter")
        if trigger == "minecraft:cured_zombie_villager":
            return self._cure_zombie_node()
        if trigger == "minecraft:kill_mob_near_sculk_catalyst":
            return self._sculk_kill_node(cond)
        if trigger == "minecraft:bee_nest_destroyed":
            # Break a bee nest — with whatever tool the criterion demands. Total Beelocation wants
            # Silk Touch (otherwise the nest just breaks and the bees are lost), and only the bee
            # was being asked for, so it read green on a world with no way to enchant anything.
            return self._all_opt(self._entity_gid("minecraft:bee"),
                                 self._item_predicate(cond.get("item")))
        if trigger == "minecraft:allay_drop_item_on_block":
            return self._all_req(self._entity_gid("minecraft:allay"),
                                 self.h.acquire("minecraft:note_block"))
        if trigger == "minecraft:ride_entity_in_lava":
            return self._all_req(self._entity_gid("minecraft:strider"),
                                 self.h.access_region(REGION_NETHER))
        if trigger == "minecraft:started_riding":
            return self._started_riding_node(cond)
        if trigger in ("minecraft:voluntary_exile", "minecraft:hero_of_the_village"):
            # A raid: trigger / win it → reach a Pillager and a village.
            return self._all_req(self._entity_gid("minecraft:pillager"), self.h.any_village())
        if trigger == "minecraft:avoid_vibration":
            # Sneak past a sculk sensor → the Deep Dark (Ancient City).
            return self._struct_gid("minecraft:ancient_city")
        if trigger in ("minecraft:channeled_lightning", "minecraft:lightning_strike",
                       "minecraft:spear_mobs"):
            # Channel lightning with a trident / spear mobs with one → obtain a trident.
            return self.h.acquire("minecraft:trident")
        if trigger == "minecraft:nether_travel":
            return self.h.access_region(REGION_NETHER)
        if trigger == "minecraft:levitation":
            # Levitate (Shulker bullets) → reach a Shulker (End City).
            return self._entity_gid("minecraft:shulker")
        if trigger == "minecraft:fall_after_explosion":
            # Be launched by an explosion → a wind charge (Breeze) or TNT.
            return self._any_opt(self.h.acquire("minecraft:wind_charge"),
                                 self.h.acquire("minecraft:tnt"))
        if trigger == "minecraft:fall_from_height":
            return self.h.access_region(REGION_OVERWORLD)  # mountains / high builds
        if trigger == "minecraft:brewed_potion":
            # Brewing anything (Local Brewery) needs the full capability — a water bottle in
            # particular (glass + water = Overworld) — not just the stand (blackstone + blaze rod).
            return self._can_brew()
        if trigger == "minecraft:effects_changed":
            return self._effects_node(cond)
        if trigger == "minecraft:used_ender_eye":
            # Throw an Eye of Ender (locate a stronghold) → obtain one (blaze powder + ender pearl).
            return self.h.acquire("minecraft:ender_eye")
        if trigger == "minecraft:tick":
            # Fires every tick: an empty criterion is trivially met (its advancement's region
            # placement still gates it); a populated one pins the requirement via a player predicate,
            # so interpret a location predicate as the `location` trigger does, else fall back.
            return and_() if not cond else self._location_node(cond)
        if trigger in ("minecraft:impossible", "minecraft:recipe_unlocked"):
            # impossible: granted by the datapack's own scoreboard logic, never by gameplay.
            # recipe_unlocked: fires when a recipe is unlocked (usually on picking up an ingredient).
            # Neither's real prerequisite is in the criterion, so defer to the parent-chain fallback.
            return None
        if trigger in _IMPLIED_ITEM:
            return self.h.acquire(_IMPLIED_ITEM[trigger])
        return None

    # -- condition extractors ----------------------------------------------
    @staticmethod
    def _ns(gid: str) -> str:
        """Normalise an id/tag-body to the ``minecraft:`` namespace when it has none — BACAP writes
        many entity types/tags bare (``turtle``, ``#raiders``), and the registries are namespaced."""
        return gid if ":" in gid else f"minecraft:{gid}"

    @staticmethod
    def _path(gid):
        """The bare path of an id (namespace dropped): ``minecraft:end_city`` / ``end_city`` /
        ``bacap:end_city`` all become ``end_city``. The registries hold one path per namespace, so
        matching on the path makes every id form resolve regardless of namespace."""
        return gid.rsplit(":", 1)[-1] if isinstance(gid, str) else gid

    def _entity_name(self, gid):
        """Our display name for an entity id in any namespace (or bare), else ``None``."""
        return self._entity_by_path.get(self._path(gid)) if isinstance(gid, str) else None

    def _struct_name(self, gid):
        """Our display name for a structure id in any namespace (or bare), else ``None``."""
        return self._struct_by_path.get(self._path(gid)) if isinstance(gid, str) else None

    def _entity_names_from_type(self, gid) -> list:
        """Display names for a `type` value — a single id or a ``#tag`` (``#raiders``,
        ``#blazeandcave:llamas``), with bare ids/tags normalised to ``minecraft:`` so the namespaced
        registries/tags resolve them."""
        if not isinstance(gid, str):
            return []
        members = (self._entity_tags.get(self._ns(gid[1:]), [])
                   if gid.startswith("#") else [gid])
        return [name for name in map(self._entity_name, members) if name is not None]

    def _entity_names(self, cond: dict) -> list:
        """Display names for the entity/entities a criterion's `entity` predicate pins via `type`."""
        return self._entity_names_from_type(self._predicate_value(cond.get("entity"), "type"))

    def _entity_node(self, cond: dict, gate=None) -> Rule | None:
        """Reach the entity/entities a criterion's `entity` predicate pins (single id or ``#tag``),
        and be able to put on it whatever that predicate says it is WEARING.

        ``gate`` is what each pinned species must satisfy, defaulting to plain reachability
        (``RuleHelper.entity``). A kill criterion passes ``can_defeat`` instead — see
        _player_killed_node."""
        gate = gate or self.h.entity
        options = [gate(name) for name in self._entity_names(cond)]
        if not options:
            return None  # callers keep their own "any mob" fallbacks for an unpinned predicate
        equipment = self._entity_equipment_node(cond)
        node = or_(*options)
        return node if equipment is None else and_(node, equipment)

    def _entity_equipment_node(self, cond: dict) -> Rule | None:
        """The gear an `entity` predicate demands the target be wearing, via its ``equipment`` map.

        A predicate can pin more than a species: Good as New wants "a wolf whose body slot holds
        undamaged wolf armor", and the wolf on its own is trivially reachable, so ignoring the
        equipment collapsed the whole criterion to a bare Overworld check — no wolf armor, and so
        no armadillo scutes and no crafting Knowledge either. Each slot resolves through the
        ordinary item predicate, so an enchantment or trim on the gear comes along with it; a slot
        that resolves to nothing is skipped rather than voiding the gate."""
        equipment = self._predicate_value(cond.get("entity"), "equipment")
        if not isinstance(equipment, dict):
            return None
        parts = [self._item_predicate(slot) for name, slot in equipment.items()
                 if name in _EQUIPMENT_SLOTS]
        parts = [part for part in parts if part is not None]
        return and_(*parts) if parts else None

    def _entity_killed_player_node(self, cond: dict) -> Rule | None:
        """``entity_killed_player``: a non-player entity kills you. An armor stand (BACAP's Living
        Dummy) can only deal damage once you've built and kitted out an animated dummy — gate it on a
        crafted armor stand plus the armour-handling knowledge, an enchanting table and iron-tier
        material the kit demands. Otherwise reaching the killer is the gate."""
        if self._path(self._predicate_value(cond.get("entity"), "type")) == "armor_stand":
            return self._all_req(
                self.h.acquire("minecraft:armor_stand"),
                self.h.knowledge(K_ARMOR),
                self.h.acquire("minecraft:enchanting_table"),
                self.h.material(MAT_IRON),
            )
        node = self._entity_node(cond)
        if node is not None:
            return node
        # Empty conditions = "be killed by any mob" (the Adventure root). Needs a mob to exist — under
        # mob_spawn_lock that gates on unlocking one; can_kill_any_mob is the "some mob reachable" gate.
        return self.h.can_kill_any_mob() if not cond else None

    def _excluded_entity_names(self, cond: dict) -> list:
        """Display names an INVERTED `entity` predicate excludes (Lead the Way!'s vehicle/non-mob
        list), resolved through the same namespaced registry/tags as ``_entity_names`` — so an
        'any entity except X' constraint stays data-driven instead of assuming what X is."""
        entries = cond.get("entity")
        entries = entries if isinstance(entries, list) else [entries]
        names = []
        for sub in entries:
            if not isinstance(sub, dict) or not str(sub.get("condition", "")).endswith("inverted"):
                continue
            term = sub.get("term")
            if isinstance(term, dict):
                names.extend(self._entity_names({"entity": term.get("predicate", term)}))
        return names

    @staticmethod
    def _min_level(level) -> int:
        """The minimum value a ``construct_beacon`` ``level`` condition demands — an exact int, the
        ``min`` of a bounds object, or 0 when unconstrained (a level-0 beacon needs no pyramid)."""
        if isinstance(level, bool):
            return 0
        if isinstance(level, (int, float)):
            return int(level)
        if isinstance(level, dict):
            lo = level.get("min")
            return int(lo) if isinstance(lo, (int, float)) and not isinstance(lo, bool) else 0
        return 0

    @staticmethod
    def _has_tag(holder: dict, tag_id: str) -> bool:
        """Whether a damage-type / killing-blow block expects a given damage `#tag` (is_projectile,
        is_player_attack, …)."""
        return any(isinstance(t, dict) and t.get("id") == tag_id and t.get("expected", True)
                   for t in (holder.get("tags") or []))

    def _projectile_item(self, proj: str) -> Rule | None:
        """The means to land a hit with a given projectile entity: a trident, a bow/crossbow + arrow,
        or — for any other throwable (wind charge, ender pearl, snowball, splash potion, …) — that
        item (the projectile entity id is the throwable item id)."""
        if "trident" in proj:
            return self.h.can_get_trident()
        if "arrow" in proj:
            return self._all_req(
                self._any_opt(self.h.acquire("minecraft:bow"), self.h.acquire("minecraft:crossbow")),
                self.h.can_get_arrow(),
            )
        if "area_effect_cloud" in proj or proj.endswith("lingering_potion"):
            # A lingering cloud is left by a lingering potion the player threw (Gas!, Multiclassed).
            return self.h.acquire("minecraft:lingering_potion")
        return self.h.acquire(proj)

    def _weapon_node(self, damage) -> Rule | None:
        """The weapon a `damage` predicate pins: a specific item held in the attacker's mainhand (the
        direct or source entity — a mace smash pins the mace on `direct_entity`, Multiclassed's
        per-tool tags pin it on `source_entity`), else its projectile (`type.direct_entity`, a single
        id or a list of alternatives), else any melee player attack (`is_player_attack`) → a real melee
        weapon. None when nothing is pinned."""
        dtype = (damage or {}).get("type") or {}
        direct = dtype.get("direct_entity")
        # A wielded weapon takes priority over treating the direct entity as a thrown projectile —
        # a real projectile (arrow/snowball/…) has no mainhand equipment, so it still falls through.
        for actor in (direct, dtype.get("source_entity")):
            held = self._mainhand_weapon([{"predicate": actor}]) if isinstance(actor, dict) else None
            if held is not None:
                return held
        proj = direct.get("type") if isinstance(direct, dict) else None
        if isinstance(proj, str):
            return self._projectile_item(proj)
        if isinstance(proj, list):
            return self._any_opt(*[self._projectile_item(p) for p in proj if isinstance(p, str)])
        if self._has_tag(dtype, "minecraft:is_player_attack"):
            return self.h.can_kill()
        return None

    def _player_hurt_node(self, cond: dict) -> Rule | None:
        """``player_hurt_entity``: the player damages an entity. Require the pinned weapon AND, when a
        specific victim is pinned, reaching it. An unpinned victim is any mob (trivially reachable),
        so the weapon is the real gate."""
        weapon = self._weapon_node(cond.get("damage"))
        if weapon is None:
            # No weapon pinned on `damage` (a damage-type #tag like mace_smash names no item): the held
            # weapon may instead sit on the player's mainhand equipment (Nice to Mace You! → a mace).
            weapon = self._mainhand_weapon(cond.get("player"))
        victim = self._entity_node(cond)
        if victim is None and self._has_lava_fluid(cond):
            # The victim is pinned only as "an entity in lava" (I'm in Lava With You names no type).
            # Lava is not Nether-exclusive — an Overworld lava pool works too — so don't force a
            # Strider/Nether; any reachable mob you can hit (bare-handed is fine) satisfies it.
            victim = self.h.can_kill_any_mob()
        parts = [n for n in (weapon, victim) if n is not None]
        return and_(*parts) if parts else None

    def _has_lava_fluid(self, cond: dict) -> bool:
        """Whether any `entity`/`player` sub-condition requires standing in lava (a fluid predicate)."""
        for key in ("entity", "player"):
            entries = cond.get(key)
            for sub in (entries if isinstance(entries, list) else [entries]):
                pred = sub.get("predicate") if isinstance(sub, dict) else None
                location = pred.get("location") if isinstance(pred, dict) else None
                fluid = location.get("fluid") if isinstance(location, dict) else None
                fluids = fluid.get("fluids") if isinstance(fluid, dict) else None
                if isinstance(fluids, list) and any("lava" in f for f in fluids):
                    return True
        return False

    def _mainhand_weapon(self, player) -> Rule | None:
        """The item a player predicate requires held in the mainhand, as acquisition logic."""
        equipment = self._predicate_value(player, "equipment")
        mainhand = equipment.get("mainhand") if isinstance(equipment, dict) else None
        return self._item_predicate(mainhand) if isinstance(mainhand, dict) else None

    def _player_killed_node(self, cond: dict) -> Rule | None:
        """``player_killed_entity``: kill an entity. The victim is on `entity`; any weapon constraint is
        on `killing_blow` (a projectile, the killer's held mainhand item, or a melee player-attack).

        The victim gate is can_defeat, not plain reachability: killing something is not the same as
        standing next to it. For an ordinary mob the two are identical (can_defeat says so — anything
        is beatable bare-handed), so this only bites on the four bosses, which is exactly where it
        should. "Free the End" compiled to a bare Region(The End): no bow, no gear, no fight. It also
        puts the kill ADVANCEMENTS on the same footing as the Kill/Boss Kill LOCATIONS, which have
        always used can_defeat (engine.collect_entity_rules)."""
        weapon = self._killing_blow_weapon(cond.get("killing_blow"))
        victim = self._entity_node(cond, gate=self.h.can_defeat)
        parts = [n for n in (weapon, victim) if n is not None]
        if victim is None and cond.get("entity"):
            # Victim pinned only by what it wears (Trick or Treat!: kill any mob in a carved pumpkin):
            # need a way to kill a mob, plus that worn item.
            worn = self._equipment_nodes(self._predicate_value(cond.get("entity"), "equipment"))
            if worn:
                parts.append(self.h.can_kill())
                parts.extend(worn)
        if parts:
            return and_(*parts)
        # Empty conditions = "kill any mob" (the Adventure root). Needs at least one mob reachable —
        # NOT trivially true: under mob_spawn_lock every mob is gated behind its unlock item.
        return self.h.can_kill_any_mob() if not cond else None

    def _killing_blow_weapon(self, kb) -> Rule | None:
        if not isinstance(kb, dict):
            return None
        direct = kb.get("direct_entity")
        proj = direct.get("type") if isinstance(direct, dict) else None
        if isinstance(proj, str):
            return self._projectile_item(proj)
        source = kb.get("source_entity")
        mainhand = ((source.get("equipment") or {}).get("mainhand")
                    if isinstance(source, dict) else None)
        held = self._item_predicate(mainhand) if isinstance(mainhand, dict) else None
        if held is not None:
            return held
        if self._has_tag(kb, "minecraft:is_player_attack"):
            return self.h.can_kill()
        if self._has_tag(kb, "minecraft:is_projectile"):
            # Killed by an unspecified projectile (There it goes…) → a bow/crossbow + arrow.
            return self._all_req(
                self._any_opt(self.h.acquire("minecraft:bow"), self.h.acquire("minecraft:crossbow")),
                self.h.can_get_arrow(),
            )
        return None

    def _entity_hurt_player_node(self, cond: dict) -> Rule | None:
        """``entity_hurt_player``: the player takes damage. Reach the attacker pinned on
        `damage.source_entity` (or `entity`); a *blocked* hit also needs a shield — plus that attacker,
        or any projectile-shooting mob when none is named (deflecting a projectile)."""
        damage = cond.get("damage") or {}
        source = damage.get("source_entity")
        attacker = (self._entity_gid(source.get("type"))
                    if isinstance(source, dict) and isinstance(source.get("type"), str) else None)
        if attacker is None:
            attacker = self._entity_node(cond)
        if damage.get("blocked"):
            shooter = attacker or self._any_opt(*[self._entity_gid(g) for g in _PROJECTILE_SHOOTERS])
            return self._all_req(self.h.acquire("minecraft:shield"), shooter)
        if attacker is None:
            return self._dimpen_node(damage)  # arrow tagged through every dimension (Dimension Penetration)
        return attacker

    # dimpen_<dim> scoreboard tags BACAP writes on the arrow once it has flown through that dimension.
    _DIMPEN_REGION = {"overworld": REGION_OVERWORLD, "nether": REGION_NETHER, "end": REGION_END}

    def _dimpen_node(self, damage: dict) -> Rule | None:
        """Dimension Penetration: be hit by an arrow that has passed through every dimension. The
        ``dimpen_overworld/nether/end`` nbt tags name the dimensions, so require a bow/crossbow + arrow
        and access to each one named."""
        direct = (damage.get("type") or {}).get("direct_entity")
        nbt = direct.get("nbt") if isinstance(direct, dict) else None
        if not isinstance(nbt, str) or "dimpen_" not in nbt:
            return None
        regions = {self._DIMPEN_REGION[m] for m in re.findall(r"dimpen_(\w+)", nbt)
                   if m in self._DIMPEN_REGION}
        weapon = self._all_req(
            self._any_opt(self.h.acquire("minecraft:bow"), self.h.acquire("minecraft:crossbow")),
            self.h.can_get_arrow())
        return self._all_req(weapon, *[self.h.access_region(r) for r in sorted(regions)])

    def _killed_by_arrow_node(self, cond: dict) -> Rule | None:
        """``killed_by_arrow``: kill with an arrow/projectile. The victim(s) are on ``victims`` (an AND
        of OR-groups), not ``entity``; the launcher is on ``fired_from_weapon``. Require the weapon
        (the pinned one, else any bow/crossbow + arrow) AND defeating each pinned victim group."""
        weapon = self._any_acquire((cond.get("fired_from_weapon") or {}).get("items"))
        if weapon is None:
            weapon = self._all_req(
                self._any_opt(self.h.acquire("minecraft:bow"), self.h.acquire("minecraft:crossbow")),
                self.h.can_get_arrow(),
            )
        parts = [weapon] if weapon is not None else []
        for group in cond.get("victims") or []:
            options = [self.h.can_defeat(name) for name in self._victim_names(group)]
            if options:
                parts.append(or_(*options))
        return and_(*parts) if parts else None

    def _victim_names(self, group) -> list:
        """Entity display names a ``killed_by_arrow`` ``victims`` OR-group pins (each entry an
        ``entity_properties`` condition whose ``predicate.type`` is an id or a ``#tag``)."""
        entries = group if isinstance(group, list) else [group]
        names = []
        for sub in entries:
            gid = (sub.get("predicate") or {}).get("type") if isinstance(sub, dict) else None
            if not isinstance(gid, str):
                continue
            members = (self._entity_tags.get(self._ns(gid[1:]), [])
                       if gid.startswith("#") else [gid])
            names.extend(n for n in map(self._entity_name, members) if n is not None)
        return names

    def _recipe_id_node(self, recipe_id) -> Rule | None:
        """``recipe_crafted`` with no ``ingredients`` (only a ``recipe_id``). An armor-trim smithing
        recipe (``<template>_smithing_trim``) → a smithing table + that trim template (structure loot);
        otherwise treat the recipe id as its crafted item id and acquire that (cake, melon, templates)."""
        if not isinstance(recipe_id, str):
            return None
        base = recipe_id.split(":", 1)[-1]
        suffix = "_smithing_trim"
        if base.endswith(suffix):
            template = base[: -len(suffix)]
            return self._all_req(self.h.acquire("minecraft:smithing_table"),
                                 self.h.acquire(f"minecraft:{template}"))
        return self.h.acquire(recipe_id)

    def _location_node(self, cond: dict) -> Rule | None:
        """The `player` predicate, in any of its forms: a dict with `type_specific` (advancement/stat
        prerequisites — BACAP's Milestones), or a list of entity_properties / any_of / inverted
        conditions pinning a location, worn equipment, or the block stood on."""
        return self._player_node(cond.get("player"))

    def _player_node(self, player) -> Rule | None:
        if isinstance(player, dict):
            return self._type_specific_node(player.get("type_specific"))
        if isinstance(player, list):
            parts = [self._condition_node(sub) for sub in player]
            parts = [p for p in parts if p is not None]
            return self._all_req(*parts) if parts else None
        return None

    def _condition_node(self, sub) -> Rule | None:
        """One predicate-condition: an `any_of`/`all_of` group (recursed), an inverted refinement
        (ignored — "NOT somewhere" adds no positive gate), or an entity_properties/location predicate."""
        if not isinstance(sub, dict):
            return None
        ctype = str(sub.get("condition", ""))
        if ctype.endswith("inverted"):
            return None
        if ctype.endswith(("any_of", "all_of")):
            opts = [self._condition_node(t) for t in sub.get("terms", [])]
            opts = [o for o in opts if o is not None]
            if not opts:
                return None
            return or_(*opts) if ctype.endswith("any_of") else and_(*opts)
        return self._predicate_loc_node(sub.get("predicate", sub))

    def _predicate_loc_node(self, pred) -> Rule | None:
        """A single predicate's location / worn-equipment / stepping-on gates, AND-ed."""
        if not isinstance(pred, dict):
            return None
        parts = []
        loc = pred.get("location")
        if isinstance(loc, dict):
            node = self._loc_value_node(loc)
            if node is not None:
                parts.append(node)
        parts += self._equipment_nodes(pred.get("equipment"))
        stepping = pred.get("stepping_on")
        if isinstance(stepping, dict):
            ids = self._block_ids(stepping.get("block"))
            block = self._block_region_node(ids) or self._any_acquire(ids)
            if block is not None:
                parts.append(block)
        effects = pred.get("effects")
        if isinstance(effects, dict):
            # Standing somewhere while holding an effect (Marine Marauder's Water Breathing in water):
            # the gate is gaining the effect; the paired fluid/location condition is not a logic gate.
            parts.append(self._effect_node(effects))
        vehicle = pred.get("vehicle")
        if isinstance(vehicle, dict):
            node = self._vehicle_node(vehicle)
            if node is not None:
                parts.append(node)
        ts = pred.get("type_specific")
        if isinstance(ts, dict):
            # A list-form `player` predicate can carry the advancement/stat prerequisites that the
            # dict form puts straight on `type_specific` (Insomniac's time_since_rest stat).
            node = self._type_specific_node(ts)
            if node is not None:
                parts.append(node)
        return self._all_req(*parts) if parts else None

    def _loc_value_node(self, loc: dict) -> Rule | None:
        struct = loc.get("structures")
        if isinstance(struct, str):
            if struct.startswith("#"):
                # A structure #tag — the village tag is the only common one we can map.
                return self.h.any_village() if "village" in struct else None
            name = self._struct_name(struct)
            return self.h.structure(name) if name else None
        if "biomes" in loc:
            # Locating a specific biome needs the Biome Finder (when enabled) AND being in that biome's
            # dimension — a Nether/End biome (basalt_deltas, the_end) carries its region (do NOT rely
            # on placement, which is uniformly Overworld); an Overworld biome gates on the Overworld.
            biomes = loc["biomes"]
            region = self._biome_region(biomes if isinstance(biomes, str) else "")
            return self._all_req(self.h.needs_biome_finder(), self.h.access_region(region))
        dim = loc.get("dimension")
        region = _DIMENSION_REGION.get(self._path(dim)) if isinstance(dim, str) else None
        if region:
            return self.h.access_region(region)
        if "position" in loc or "light" in loc or "fluid" in loc:
            # A coordinate / light-level / standing-in-fluid threshold isn't a logic gate (Heart of
            # Darkness is just "be somewhere dark"; the fluid half of Marine Marauder / Stayin' Frosty
            # pairs with an effect that is the real gate). Region placement still applies.
            return and_()
        return None

    # Biomes that only exist in the Nether / the End; everything else is an Overworld biome.
    _NETHER_BIOMES = frozenset({
        "nether_wastes", "crimson_forest", "warped_forest", "soul_sand_valley", "basalt_deltas"})
    _END_BIOMES = frozenset({
        "the_end", "end_highlands", "end_midlands", "end_barrens", "small_end_islands"})

    def _biome_region(self, biome) -> str:
        """The region a biome id sits in (Overworld unless it is a known Nether/End biome)."""
        path = self._path(biome)
        if path in self._NETHER_BIOMES:
            return REGION_NETHER
        if path in self._END_BIOMES:
            return REGION_END
        return REGION_OVERWORLD

    def _type_specific_node(self, ts) -> Rule | None:
        """A `type_specific` player predicate: prerequisite advancements (BACAP Milestones reach
        another advancement) and/or stats (a `mined` stat → being able to obtain that block)."""
        if not isinstance(ts, dict):
            return None
        parts = []
        advancements = ts.get("advancements")
        if isinstance(advancements, dict):
            for gid, required in advancements.items():
                if required is False:
                    continue
                loc = self._adv_loc_by_gid.get(gid)
                if loc is None or (self._active is not None and loc not in self._active):
                    return None  # depends on an advancement not present this seed
                parts.append(self.h.reached(loc))
        for stat in (ts.get("stats") or []):
            if not isinstance(stat, dict):
                continue
            stat_type, stat_id = self._path(stat.get("type")), stat.get("stat")
            if stat_type == "mined" and isinstance(stat_id, str):
                node = self.h.acquire(stat_id)
            elif stat_type == "killed" and isinstance(stat_id, str):
                # Kill N of a mob (Ring of the End: 20 Ender Dragons; Iceologer: 100 Glow Squids) →
                # the count isn't a gate, but defeating that mob is (can_defeat carries boss logic).
                name = self._entity_name(stat_id)
                node = self.h.can_defeat(name) if name in MOBS_ALL else None
            elif stat_type == "custom" and stat_id in _CUSTOM_STAT_ITEM:
                # A custom counter we can map to an item: eating N cake slices (Must be your birthday)
                # just needs access to cake.
                node = self.h.acquire(_CUSTOM_STAT_ITEM[stat_id])
            elif stat_type == "custom" and stat_id in _CUSTOM_STAT_TRIVIAL:
                node = and_()  # a pure time counter (Insomniac) — no gate; region placement applies
            else:
                return None  # an unmapped custom counter stat isn't derivable
            if node is None:
                return None
            parts.append(node)
        return self._all_req(*parts) if parts else None

    def _equipment_nodes(self, equipment) -> list:
        """Acquisition nodes for the items an `equipment` predicate requires worn (per slot)."""
        if not isinstance(equipment, dict):
            return []
        nodes = []
        for slot_pred in equipment.values():
            node = self._item_predicate(slot_pred)
            if node is not None:
                nodes.append(node)
        return nodes

    @staticmethod
    def _block_ids(value) -> list:
        """Block ids from a `block` predicate value: a bare id, or a ``{"blocks": id|[ids]}`` form."""
        if isinstance(value, str):
            return [value]
        if isinstance(value, dict):
            ids = value.get("blocks")
            if isinstance(ids, str):
                return [ids]
            if isinstance(ids, list):
                return [b for b in ids if isinstance(b, str)]
        return []

    def _inventory_node(self, cond: dict) -> Rule | None:
        """``inventory_changed``: every required item (the `items` list) AND any worn gear pinned via a
        `player.equipment` predicate (BACAP's "wear iron armor" advancements)."""
        parts = []
        items = self._all_items(cond.get("items"))
        if items is not None:
            parts.append(items)
        parts += self._equipment_nodes(self._predicate_value(cond.get("player"), "equipment"))
        return self._all_req(*parts) if parts else None

    def _all_items(self, predicates) -> Rule | None:
        """AND over a list of item predicates (an ``inventory_changed`` items list or a
        ``recipe_crafted`` ingredients list): every one must be obtainable, else fall back."""
        if not isinstance(predicates, list) or not predicates:
            return None
        parts: list[Rule] = []
        for pred in predicates:
            node = self._item_predicate(pred)
            if node is None:
                return None  # a required item we can't resolve → fall back entirely
            parts.append(node)
        return and_(*parts)

    def _species_from_components(self, cond: dict) -> str | None:
        """Infer the species of a variant-only entity predicate (e.g. a cat colour, which pins no
        ``type``) from its component key ``minecraft:<species>/variant``."""
        components = self._predicate_value(cond.get("entity"), "components")
        if not isinstance(components, dict):
            return None
        for key in components:
            match = re.match(r"minecraft:([a-z_]+)/variant", key)
            if match:
                return self._entity_name(match.group(1))
        return None

    def _item_predicate(self, pred) -> Rule | None:
        """Resolve one item predicate (``{"items": <id|[ids]|#tag>}``) to acquisition logic — or,
        for a potion item carrying a ``potion_contents`` component, its brewing chain. When the
        predicate also demands the item be ENCHANTED, the enchanting-capability gate is AND-ed in
        (else "an enchanted sword" would compile as merely "a sword")."""
        if not isinstance(pred, dict):
            return None
        potion = self._potion_node(pred)
        if potion is not None:
            return potion
        # The base item (when named) AND any capability its predicate demands: being enchanted, or
        # carrying an armor trim of a specific material (Chromatic Armory / Coordinated Flair).
        parts = [self._any_acquire(pred.get("items")), self._enchant_gate(pred), self._trim_gate(pred)]
        parts = [p for p in parts if p is not None]
        return and_(*parts) if parts else None

    def _trim_gate(self, pred: dict) -> Rule | None:
        """The capability behind a ``trim`` item predicate: a smithing table plus the named trim
        material (``None`` when the predicate names no resolvable trim material)."""
        predicates = pred.get("predicates")
        if not isinstance(predicates, dict):
            return None
        trim = predicates.get("minecraft:trim") or predicates.get("trim")
        material = trim.get("material") if isinstance(trim, dict) else None
        item = _TRIM_MATERIAL_ITEM.get(self._path(material)) if isinstance(material, str) else None
        if item is None:
            return None
        return self.h.all_of(self.h.acquire("minecraft:smithing_table"),
                             self.h.acquire(f"minecraft:{item}"))

    def _enchant_gate(self, pred: dict) -> Rule | None:
        """The capability to get an ENCHANTED item, or ``None`` when the predicate names no
        enchantment. Two routes: the enchanting table (``acquire`` gates it behind Knowledge:
        Enchanting + its tier), OR an enchanted book — a librarian's trade gives one with no
        Knowledge needed, applied to the item with an anvil (when the item itself, not the book,
        must carry the enchantment, i.e. ``enchantments`` predicate vs ``stored_enchantments``)."""
        predicates = pred.get("predicates")
        if not isinstance(predicates, dict):
            return None
        # Component keys are namespaced in the data ("minecraft:enchantments"); accept the bare form
        # too, the way _criterion normalises triggers and _trim_gate already reads its own key. Only
        # the bare spelling was matched, so an enchantment requirement silently evaluated to "no
        # enchantment needed" — Total Beelocation asks for Silk Touch and compiled to "reach a bee".
        on_item = "enchantments" in predicates or "minecraft:enchantments" in predicates
        on_book = ("stored_enchantments" in predicates
                   or "minecraft:stored_enchantments" in predicates)
        if not on_item and not on_book:
            return None
        routes = [self.h.acquire("minecraft:enchanting_table")]
        book = self.h.acquire("minecraft:enchanted_book")  # librarian trades; no Knowledge needed
        if book is not None:
            # A stored_enchantments target *is* the book; an enchantments target needs it applied
            # with an anvil. (A librarian's book has a random enchant — a trade path, as elsewhere.)
            routes.append(and_(book, self.h.acquire("minecraft:anvil")) if on_item else book)
        return or_(*routes)

    def _potion_node(self, pred: dict) -> Rule | None:
        """A specific brewed potion: a brewing stand + Knowledge: Brewing + a glass bottle + every
        reagent of its type (see brewing.json). Returns ``None`` for a non-potion or an untyped
        potion (which falls through to its loot/trade sources)."""
        items = pred.get("items")
        ids = items if isinstance(items, list) else [items]
        if not any(item in _POTION_ITEMS for item in ids):
            return None
        contents = self._component(pred, "minecraft:potion_contents")
        potion_type = contents.get("potion") if isinstance(contents, dict) else None
        if not isinstance(potion_type, str):
            return None
        potion_type = potion_type.split(":")[-1]
        for prefix in ("long_", "strong_"):
            potion_type = potion_type.removeprefix(prefix)
        reagents = _brewing().get(potion_type)
        if reagents is None:
            return None
        parts = [self._can_brew()]
        parts += [self.h.acquire(f"minecraft:{reagent}") for reagent in reagents]
        parts = [node for node in parts if node is not None]
        return and_(*parts) if parts else None

    def _can_brew(self) -> Rule | None:
        """Capability to brew a potion: a brewing stand, Knowledge: Brewing, and a water bottle — a
        glass bottle (glass = sand) filled with water. Both sand and water are Overworld-only, so
        brewing gates on the Overworld even though the stand itself is buildable from Nether
        blackstone + a blaze rod."""
        return self.h.all_of(self.h.acquire("minecraft:brewing_stand"), self.h.knowledge(K_BREWING),
                             self.h.acquire("minecraft:glass_bottle"))

    @staticmethod
    def _component(pred: dict, key: str):
        """A component value from an item predicate's ``components`` / ``predicates`` block."""
        for holder in ("components", "predicates"):
            block = pred.get(holder)
            if isinstance(block, dict) and key in block:
                return block[key]
        return None

    def _any_acquire(self, ids) -> Rule | None:
        """OR over ``acquire`` of one item id, a list of them, or an item ``#tag`` (the items are
        alternatives; a tag expands to its members)."""
        if isinstance(ids, str):
            ids = [ids]
        if not isinstance(ids, list) or not ids:
            return None
        options = []
        for item_id in ids:
            for resolved in self._expand_item(item_id):
                node = self.h.acquire(resolved)
                if node is not None:
                    options.append(node)
        return or_(*options) if options else None

    def _expand_item(self, item_id: str) -> list:
        """An item id as-is, or a ``#tag`` expanded to its members. A tag in a block context
        (``stepping_on``/``placed_block`` reference a *block* tag like ``#minecraft:ice``) isn't in
        the item-tag table, so fall back to the block-tag table — its members are same-named items
        for ``acquire`` (a non-obtainable member like ``frosted_ice`` simply drops out of the OR)."""
        if isinstance(item_id, str) and item_id.startswith("#"):
            body = item_id[1:]
            return self._item_tags.get(body) or self._block_tags.get(body, [])
        return [item_id] if isinstance(item_id, str) else []

    def _used_item_node(self, cond: dict) -> Rule | None:
        """``using_item`` / ``shot_crossbow``: obtain the item, and — when the criterion pins what
        the player is aiming at — also reach that entity."""
        item = self._item_predicate(cond.get("item"))
        if item is None:
            return None
        specific = self._predicate_value(cond.get("player"), "type_specific")
        looking = specific.get("looking_at") if isinstance(specific, dict) else None
        gid = looking.get("type") if isinstance(looking, dict) else None
        target = self._entity_name(gid) if gid else None
        return and_(item, self.h.entity(target)) if target in MOBS_ALL else item

    # A placed crop block isn't itself an item — placing it means using its seed. Map the crops whose
    # block id differs from the planting item; every other block places from a same-named item.
    _PLANT_ITEM = {
        "minecraft:torchflower_crop": "minecraft:torchflower_seeds",
        "minecraft:pitcher_crop": "minecraft:pitcher_pod",
        "minecraft:wheat": "minecraft:wheat_seeds",
        "minecraft:beetroots": "minecraft:beetroot_seeds",
        "minecraft:carrots": "minecraft:carrot",
        "minecraft:potatoes": "minecraft:potato",
        "minecraft:pumpkin_stem": "minecraft:pumpkin_seeds",
        "minecraft:melon_stem": "minecraft:melon_seeds",
        "minecraft:cocoa": "minecraft:cocoa_beans",
        "minecraft:sweet_berry_bush": "minecraft:sweet_berries",
        "minecraft:nether_wart": "minecraft:nether_wart",
        "minecraft:bamboo_sapling": "minecraft:bamboo",
        # Fire blocks aren't items — they're placed by igniting a surface with flint and steel or a
        # fire charge (a value may be a list of alternative placing items, OR-ed in _placed_block_node).
        "minecraft:fire": ["minecraft:flint_and_steel", "minecraft:fire_charge"],
        "minecraft:soul_fire": ["minecraft:flint_and_steel", "minecraft:fire_charge"],
    }

    def _placed_block_node(self, cond: dict) -> Rule | None:
        """``placed_block``: obtain the item that places the block — its seed (crops), the same-named
        block item, or the tool the criterion pins on ``match_tool`` (a cod bucket, scaffolding, …) —
        AND any block the criterion requires ADJACENT to it. The placement can be at any of several
        positions/orientations (an ``any_of`` of ``location_check`` cells, e.g. The Power of Books'
        chiseled bookshelf needing a comparator beside it), so the location conditions are walked as a
        logic tree (list / ``all_of`` = AND, ``any_of`` = OR) rather than flattened into one OR."""
        location = cond.get("location")
        entries = location if isinstance(location, list) else [location]
        placed_items: list = []   # the no-offset block being placed → its placing item(s)
        context: list = []        # required adjacent blocks (offset / grouped) → AND-ed in
        for entry in entries:
            if not isinstance(entry, dict):
                continue
            ctype = str(entry.get("condition", ""))
            if ctype.endswith("match_tool"):
                placed_items += self._match_tool_items({"location": [entry]})
            elif ctype.endswith(("any_of", "all_of")) or self._has_offset(entry):
                node = self._block_context_node(entry)
                if node is not None:
                    context.append(node)
            else:  # a no-offset block_state_property / location_check → the placed block
                for block in self._entry_block_ids(entry):
                    mapped = self._PLANT_ITEM.get(block, block)
                    placed_items += mapped if isinstance(mapped, list) else [mapped]
        if self._requires_water(entries):  # waterlogged placement needs water → Overworld|End
            water = self._block_region_node(["water"])
            if water is not None:
                context.append(water)
        parts = [n for n in (self._any_acquire(placed_items), *context) if n is not None]
        return and_(*parts) if parts else None

    @classmethod
    def _requires_water(cls, entries: list) -> bool:
        """True if any location condition pins ``waterlogged: "true"`` — the block must be placed in
        water (e.g. Stay Hydrated!'s dried ghast), which can't exist in the Nether. Recurses through
        ``any_of`` / ``all_of`` groups."""
        for entry in entries:
            if not isinstance(entry, dict):
                continue
            props = entry.get("properties")
            if isinstance(props, dict) and str(props.get("waterlogged", "")).lower() == "true":
                return True
            if cls._requires_water(entry.get("terms", [])):
                return True
        return False

    @staticmethod
    def _has_offset(entry: dict) -> bool:
        return any(k in entry for k in ("offsetX", "offsetY", "offsetZ"))

    def _entry_block_ids(self, entry: dict) -> list:
        """Block ids a single location condition names — a ``block_state_property``'s top-level
        ``block`` or a ``location_check``'s ``predicate.block``."""
        out = self._block_ids(entry.get("block"))
        pred = entry.get("predicate")
        if isinstance(pred, dict):
            out += self._block_ids(pred.get("block"))
        return out

    def _block_context_node(self, entry: dict) -> Rule | None:
        """A required-block location condition as a gate, recursing through ``any_of`` (OR) / ``all_of``
        (AND) groups; each block leaf → reach its dimension or obtain it. ``None`` when unresolvable."""
        if not isinstance(entry, dict):
            return None
        ctype = str(entry.get("condition", ""))
        if ctype.endswith("inverted"):
            return None
        if ctype.endswith(("any_of", "all_of")):
            opts = [self._block_context_node(t) for t in entry.get("terms", [])]
            opts = [o for o in opts if o is not None]
            if not opts:
                return None
            return or_(*opts) if ctype.endswith("any_of") else self._all_req(*opts)
        ids = self._entry_block_ids(entry)
        mapped: list = []
        for block in ids:
            m = self._PLANT_ITEM.get(block, block)
            mapped += m if isinstance(m, list) else [m]
        return self._block_region_node(ids) or self._any_acquire(mapped)

    def _match_tool_items(self, cond: dict) -> list:
        """Item ids a ``match_tool`` condition pins (BACAP names the placing item this way)."""
        out = []
        location = cond.get("location")
        for entry in (location if isinstance(location, list) else [location]):
            if isinstance(entry, dict) and str(entry.get("condition", "")).endswith("match_tool"):
                ids = (entry.get("predicate") or {}).get("items")
                if isinstance(ids, list):
                    out.extend(i for i in ids if isinstance(i, str))
                elif isinstance(ids, str):
                    out.append(ids)
        return out

    def _blocks_in(self, cond: dict) -> list:
        """Every block id a block-interaction criterion references. Tolerates the flat
        ``location:[{block: id}]`` form (vanilla ``block_state_property``) and BACAP's nested
        ``location_check`` → ``predicate.block.blocks`` form, plus a top-level ``block`` predicate
        (``enter_block``) and ``any_of``/``all_of`` ``terms`` groups (Locked and Loaded's chained
        shelves). ``#tag`` ids are kept — ``_any_acquire`` expands them."""
        blocks: list = []

        def add(value):
            if isinstance(value, str):
                blocks.append(value)
            elif isinstance(value, dict):
                ids = value.get("blocks")
                if isinstance(ids, str):  # vanilla writes a single block as a bare string
                    blocks.append(ids)
                elif isinstance(ids, list):
                    blocks.extend(b for b in ids if isinstance(b, str))

        def walk(entry):
            if not isinstance(entry, dict):
                return
            add(entry.get("block"))
            pred = entry.get("predicate")
            if isinstance(pred, dict):
                add(pred.get("block"))
            for term in entry.get("terms") or []:  # nested any_of / all_of condition groups
                walk(term)

        location = cond.get("location")
        for entry in (location if isinstance(location, list) else [location]):
            walk(entry)
        add(cond.get("block"))
        add({"blocks": cond.get("blocks")})  # BACAP slide_down_block: top-level `blocks` list
        return blocks

    def _block_region_node(self, blocks: list) -> Rule | None:
        """Reach a dimension a non-item natural block pins (``_BLOCK_REGION``), OR-ed over every region
        any listed block can be in (blocks in a criterion are alternatives). ``None`` when no listed
        block pins a dimension.

        A couple of these blocks need more than their dimension: an End gateway is not part of the
        world you arrive in — it spawns when the dragon dies — so "Remote Getaway" was satisfied by
        stepping through the End portal. Its extra gate is AND-ed onto that block's own branch, so
        an OR over several blocks still lets a cheaper alternative through."""
        options = []
        for block in blocks:
            path = self._path(block)
            extra = _BLOCK_EXTRA_GATE.get(path)
            for region in _BLOCK_REGION.get(path, ()):
                node = self.h.access_region(region)
                options.append(node if extra is None else and_(node, extra(self.h)))
        return self._any_opt(*options) if options else None

    @staticmethod
    def _all_req(*nodes) -> Rule | None:
        """AND of nodes that are ALL required — ``None`` (fall back) if any is unresolved, so a
        half-built gate never silently weakens to its resolvable half."""
        return None if any(n is None for n in nodes) else and_(*nodes)

    @staticmethod
    def _all_opt(*nodes) -> Rule | None:
        """AND over the resolvable nodes; ``None`` only when nothing resolved. Unlike _all_req this
        keeps a partly-resolved gate instead of discarding it — for criteria where each part is an
        independent requirement, so dropping an unresolvable one still leaves a sound (if weaker)
        rule, and falling back to the parent chain would be weaker still."""
        present = [n for n in nodes if n is not None]
        return and_(*present) if present else None

    @staticmethod
    def _any_opt(*nodes) -> Rule | None:
        """OR over the resolvable nodes (alternative sources); ``None`` if none resolved."""
        present = [n for n in nodes if n is not None]
        return or_(*present) if present else None

    def _entity_gid(self, gid: str) -> Rule | None:
        """Reach the entity with this id (any namespace), or ``None`` when the active packs lack it."""
        name = self._entity_name(gid)
        return self.h.entity(name) if name in MOBS_ALL else None

    def _struct_gid(self, gid: str) -> Rule | None:
        """Reach the structure with this id (any namespace), or ``None`` when the packs lack it."""
        name = self._struct_name(gid)
        return self.h.structure(name) if name in STRUCTURES else None

    def _cure_zombie_node(self) -> Rule:
        """Cure a Zombie Villager: reach one, plus a golden apple and a Potion of Weakness
        (brewing stand + Knowledge: Brewing + a fermented spider eye)."""
        parts = [self._entity_gid("minecraft:zombie_villager"),
                 self.h.acquire("minecraft:golden_apple"),
                 self.h.acquire("minecraft:brewing_stand"), self.h.knowledge(K_BREWING),
                 self.h.acquire("minecraft:fermented_spider_eye")]
        return self.h.all_of(*[p for p in parts if p is not None])

    def _sculk_kill_node(self, cond: dict) -> Rule:
        """Kill a mob near a sculk catalyst → be in the Deep Dark (Ancient City) and, when the
        criterion pins a victim, reach it too."""
        deep_dark = self._struct_gid("minecraft:ancient_city")
        victim = self._entity_node(cond)
        parts = [p for p in (deep_dark, victim) if p is not None]
        return self.h.all_of(*parts) if parts else self.h.access_region(REGION_OVERWORLD)

    def _started_riding_node(self, cond: dict) -> Rule | None:
        """Ride a vehicle. The simple form pins the vehicle directly on ``player[].vehicle``; the
        nested form (Boatception's ``any_of`` over per-structure boats) hides it inside a condition
        group, which the location-predicate machinery (``_player_node``) already unwraps."""
        player = cond.get("player")
        vehicle = self._predicate_value(player, "vehicle")
        if isinstance(vehicle, dict):
            return self._vehicle_node(vehicle)
        return self._player_node(player)

    def _vehicle_node(self, vehicle: dict) -> Rule | None:
        """Be in/on a ``vehicle`` predicate: the mount (a mob reached, or a placeable boat/minecart
        acquired — a ``#tag`` expands to its members), any worn ``equipment`` (Llama Festival's
        carpet), the structure it pins (Boatception's shipwreck), and any pinned ``passenger`` (a goat
        in a boat) reached too."""
        parts = []
        vtype = vehicle.get("type")
        if isinstance(vtype, str):
            members = (self._entity_tags.get(self._ns(vtype[1:]), [])
                       if vtype.startswith("#") else [vtype])
            opts = [n for n in (self._entity_gid(m) or self.h.acquire(m) for m in members)
                    if n is not None]
            if opts:
                parts.append(or_(*opts))
        parts += self._equipment_nodes(vehicle.get("equipment"))
        loc = vehicle.get("location")
        if isinstance(loc, dict):
            node = self._loc_value_node(loc)
            if node is not None:
                parts.append(node)
        passenger = vehicle.get("passenger")
        ptype = passenger.get("type") if isinstance(passenger, dict) else None
        pass_node = self._entity_gid(ptype) if isinstance(ptype, str) else None
        if pass_node is not None:
            parts.append(pass_node)
        return self._all_req(*parts) if parts else None

    def _effects_node(self, cond: dict) -> Rule:
        """``effects_changed``: gain a status effect. Usually from a brewed potion (gate on the
        effects), but a ``source`` entity predicate means the effect is granted BY that mob — e.g.
        The Healing Power of Friendship's regeneration comes from an axolotl's kill — so it gates on
        reaching that mob (an axolotl is Overworld-only) instead of the brewing chain."""
        source = self._source_entity(cond.get("source"))
        if source is not None:
            return source
        return self._effect_node(cond.get("effects"))

    def _source_entity(self, source) -> Rule | None:
        """The mob an ``effects_changed`` ``source`` predicate names, as a reach-it gate (``None`` if
        it names no resolvable entity type)."""
        for term in (source if isinstance(source, list) else [source]):
            if not isinstance(term, dict):
                continue
            predicate = term.get("predicate")
            etype = predicate.get("type") if isinstance(predicate, dict) else None
            node = self._entity_gid(etype) if isinstance(etype, str) else None
            if node is not None:
                return node
        return None

    def _effect_node(self, effects) -> Rule:
        """Having a status effect (an ``effects`` map). Most effects come from a brewed potion, so gate
        on brewing capability; a few environmental ones map to their source."""
        names = list(effects) if isinstance(effects, dict) else []
        parts = []
        for effect in names:
            source = _EFFECT_SOURCE.get(effect)
            if source is not None:
                node = source(self)
                if node is not None:
                    parts.append(node)
        parts.append(self.h.all_of(self.h.acquire("minecraft:brewing_stand"),
                                   self.h.knowledge(K_BREWING)))
        return self.h.all_of(*parts)

    def _used_on_block_node(self, cond: dict) -> Rule | None:
        """``item_used_on_block``: require BOTH the item used (top-level ``item`` or a ``match_tool``
        predicate) AND the target block. Dropping the block let Not Quite Nine Lives pass on the
        glowstone alone without the respawn anchor (crying obsidian → Nether), and Country Lode on
        the compass without the lodestone (netherite → Nether). An unresolvable half is omitted."""
        location = cond.get("location")
        locs = location if isinstance(location, list) else [location]
        item = self._item_predicate(cond.get("item")) if "item" in cond else None
        if item is None:
            for sub in locs:
                if isinstance(sub, dict) and sub.get("condition") == "minecraft:match_tool":
                    item = self._any_acquire((sub.get("predicate") or {}).get("items"))
                    break
        blocks = self._blocks_in(cond)
        block = self._block_region_node(blocks) or self._any_acquire(blocks)
        parts = [n for n in (item, block) if n is not None]
        # A location_check can also pin the biome / dimension the block must be used IN — e.g. Sound
        # of Music needs the jukebox played in a meadow (Overworld). Gate on it so a Nether-craftable
        # jukebox alone doesn't satisfy the criterion anywhere.
        for sub in locs:
            if isinstance(sub, dict) and str(sub.get("condition", "")).endswith("location_check"):
                node = self._loc_value_node(sub.get("predicate") or {})
                if node is not None:
                    parts.append(node)
        return and_(*parts) if parts else None

    def _any_mob(self, mobs, build) -> Rule | None:
        """OR over a per-mob rule builder (``can_breed`` / ``can_tame``) for a whole mob set."""
        options = [build(name) for name in mobs]
        return or_(*options) if options else None

    def _filled_bucket_node(self, cond: dict) -> Rule | None:
        """``filled_bucket``: hold a bucket and, for a captured mob, reach it."""
        ids = (cond.get("item") or {}).get("items")
        item = ids[0] if isinstance(ids, list) and ids else ids
        if not isinstance(item, str):
            return None
        bucket = self.h.acquire("minecraft:bucket")
        if bucket is None:
            return None
        content = item.split(":")[-1].replace("_bucket", "")
        mob = self._entity_name(content)
        return and_(bucket, self.h.entity(mob)) if mob in MOBS_ALL else bucket

    # Loot tables whose name isn't a structure id: a chest that belongs to a feature the registry
    # names differently (a dungeon's monster_room, an underwater ruin's ocean_ruin).
    _LOOT_TABLE_STRUCT = {
        "simple_dungeon": "minecraft:monster_room",
        "abandoned_mineshaft": "minecraft:mineshaft",
        "woodland_mansion": "minecraft:mansion",
        "underwater_ruin_big": "minecraft:ocean_ruin_warm",
        "underwater_ruin_small": "minecraft:ocean_ruin_warm",
    }

    def _container_loot_node(self, cond: dict) -> Rule | None:
        """``player_generates_container_loot``: reach the structure whose loot table this is. The table
        path (``chests/trial_chambers/corridor``) may name the structure in any segment, via an alias
        (``simple_dungeon`` → Dungeon), or as a variant — so scan each segment most-specific-first
        against the alias map and the registry, then fall back to a shared leading segment
        (``bastion_bridge`` → Bastion Remnant)."""
        table = cond.get("loot_table")
        if not isinstance(table, str):
            return None
        segments = table.split(":")[-1].split("/")
        for seg in reversed(segments):  # 'corridor' before 'trial_chambers' before 'chests'
            name = self._struct_name(self._LOOT_TABLE_STRUCT.get(seg, seg))
            if name:
                return self.h.structure(name)
        head = segments[-1].split("_")[0]
        name = next((n for path, n in self._struct_by_path.items()
                     if path.split("_")[0] == head), None)
        return self.h.structure(name) if name else None

    @staticmethod
    def _predicate_value(entity_conditions, key: str):
        """Pull ``predicate[key]`` out of a criterion sub-condition, tolerating both the list form
        ``[{"condition": "entity_properties", "predicate": {...}}]`` and a bare ``{...}``."""
        if isinstance(entity_conditions, list):
            for sub in entity_conditions:
                if isinstance(sub, dict):
                    pred = sub.get("predicate", sub)
                    if isinstance(pred, dict) and key in pred:
                        return pred[key]
        elif isinstance(entity_conditions, dict):
            pred = entity_conditions.get("predicate", entity_conditions)
            if isinstance(pred, dict):
                return pred.get(key)
        return None
