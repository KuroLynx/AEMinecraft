"""Public data surface for the apworld.

Parsing of the content packs lives in :mod:`content.registry`. This module splits what it loads in two:

* **Version-independent** data stays module-level and is re-exported by ``from .data import *``:
  ITEMS / KNOWLEDGES (the apworld's own CSVs), TOOL_LOCKS, MATERIAL_HANDLING_ITEMS, COMMON_BIOMES, the
  BASE_ID_* / dataclasses, and — via ``from .logic.constants import *`` — the rule constants.
* **Per-Minecraft-version** data (mobs, structures, locations, the BACAP tables, the container gates)
  lives on a :class:`MinecraftContent`, one per version with packs, fetched with
  :func:`content_for`. Each player picks a version (the ``minecraft_version`` option) and reads it
  through ``world.content`` — deliberately NOT a module-level default, so code that forgets to ask
  for the player's version fails loudly instead of silently using another one.

AP's data package is static and shared by every player, so item and location ids are a UNION over
the versions: see :func:`_canonical_ids`.
"""
# NOTE: most names below are re-exported via `from .data import *` for the rest of the apworld;
# they look "unused" to linters here, so do not auto-strip them.
import dataclasses

from BaseClasses import ItemClassification  # noqa: F401

from .content.registry import (  # noqa: F401
    BASE_ID_ENTITY_UNLOCK,
    BASE_ID_ITEMS,
    BASE_ID_LOC_ADVANCEMENT,
    BASE_ID_LOC_BACAP,
    BASE_ID_LOC_BOSS_KILL,
    BASE_ID_LOC_MOB_KILL,
    BASE_ID_LOC_STRUCTURE,
    BASE_ID_STRUCT_UNLOCK,
    ContentRegistry,
    BASE_ID_KNOWLEDGE,
    MCEntityCategory,
    MCItemData,
    MCKnowledgeCategory,
    MCKnowledgeData,
    MCLocationCategory,
    MCLocationData,
    MCMobData,
    MCStructureData,
    _load_boss_kill_locations,
    _load_mob_kill_locations,
    base_pack,
    gate_knowledge_name,
    knowledge_groups,
    load_containers,
    load_manifest_advancements,
    load_manifest_challenge,
    load_manifest_removed,
    load_pack,
    load_structures,
    minecraft_versions,
    overlay_packs,
    pack_meta,
)
from .logic.constants import *

# -----------------------------------------------------------------------------------------------
# Version-independent
# -----------------------------------------------------------------------------------------------

# Items and knowledges come from the apworld's own content/*.csv, not from a pack, so any version's
# pack yields the same ones.
_ITEM_SOURCE: ContentRegistry = load_pack(base_pack(DEFAULT_MINECRAFT_VERSION))

# The curated CSV items (progression/useful + trap filler) plus every generated **filler** — the buffs
# and the safe item stacks (dirt, tuff, boats, …), all declared in filler.py under their own id block
# (filler.BASE_ID_FILLER_ITEM). Merging them here means the world registers, creates and filler-weights
# them with no CSV upkeep; the buffs live ONLY in filler.py now (no rows in items.csv). See filler.py.
from .filler import FILLER_ITEMS  # noqa: E402  (import here to avoid a top-level cycle via content)

# Knowledge gates (content/knowledges.csv), keyed by their BARE name ("Sword Handling") — the form the
# K_* constants and TOOL_LOCKS/STATION_KNOWLEDGE_LOCKS use. KNOWLEDGES_BY_CATEGORY backs the
# knowledge_gates option's category presets (tools / armor / misc / stations / containers).
KNOWLEDGES: dict[str, MCKnowledgeData] = _ITEM_SOURCE.knowledges
KNOWLEDGES_BY_CATEGORY: dict[str, list[str]] = {}
for _knowledge in KNOWLEDGES.values():
    KNOWLEDGES_BY_CATEGORY.setdefault(_knowledge.category, []).append(_knowledge.name)

# Every Knowledge is an AP item under its prefixed name; they carry their own id block, so items.csv
# and knowledges.csv can each grow without renumbering the other.
KNOWLEDGE_ITEMS: dict[str, MCItemData] = {
    knowledge.item_name: MCItemData(id=knowledge.id, classification=knowledge.classification,
                                   count=knowledge.count)
    for knowledge in KNOWLEDGES.values()
}

ITEMS: dict[str, MCItemData] = {**_ITEM_SOURCE.items, **KNOWLEDGE_ITEMS, **FILLER_ITEMS}

# Overlay packs: mod/datapack content (dumped in-game via /aem dump, then dropped into packs/)
# layered on top of a version's vanilla base. They are DISCOVERED by content.registry (every non-vanilla
# pack of that mc_version), then wired to the option that enables them via this namespace -> option map.
# Each is loaded unconditionally so the structures/locations it adds always have stable ids in the
# (static) AP data package; the option then gates whether that content is actually created/active this
# seed — exactly how `blazeandcave` gates BACAP. To add a mod/datapack: dump it, drop the pack into
# packs/, give it an option in options.py, and add its namespace here.
OVERLAY_OPTIONS: dict[str, str] = {"blazeandcave": "blazeandcave"}  # pack namespace -> enabling option

# BACAP's "Super Challenges" tab. Every entry on it is frame=challenge, so challenge_sanity's middle
# level (`frames`) has to name the tab to keep it out while still taking the challenge tiles BACAP
# scatters across mining/monsters/adventure/…
BACAP_CHALLENGES_TAB = "challenges"

# MC items gated behind Progressive Material Handling tiers: the key is the number of copies of
# "Progressive Material Handling" required to pick the item up. Tiers mirror rules.constants MAT_*
# (copper=2, iron=3, gold=4, diamond=5, netherite=6); wood/stone (0/1) are always free. The Fabric
# mod blocks pickup of these items until that many copies have been received.
MATERIAL_HANDLING_ITEMS: dict[int, list[str]] = {
    1: ["cobblestone", "stone", "cobbled_deepslate", "deepslate", "blackstone",
        "andesite", "diorite", "granite", "tuff"],
    2: ["copper_ore", "deepslate_copper_ore", "raw_copper", "copper_ingot", "copper_block", "raw_copper_block"],
    3: ["iron_ore", "deepslate_iron_ore", "raw_iron", "iron_ingot", "iron_block", "raw_iron_block", "iron_nugget"],
    4: ["gold_ore", "deepslate_gold_ore", "nether_gold_ore", "raw_gold", "gold_ingot", "gold_block",
        "raw_gold_block", "gold_nugget"],
    5: ["diamond_ore", "deepslate_diamond_ore", "diamond", "diamond_block"],
    6: ["ancient_debris", "netherite_scrap", "netherite_ingot", "netherite_block"],
}

# Tools & armor gated behind BOTH a Knowledge item AND a material tier: the value is
# (knowledge name, required "Progressive Material Handling" count). The mod blocks pickup/crafting
# until the player has received the Knowledge item AND that many Material Handling copies — e.g. a
# diamond sword needs "Knowledge: Sword Handling" and 5 Material Handling. Mirrors the apworld logic,
# where these are gated by helper.knowledge(...) plus the relevant material tier. Tune freely.
_TOOL_TIERS = {"wooden": MAT_WOOD, "stone": MAT_STONE, "copper": MAT_COPPER, "iron": MAT_IRON,
               "golden": MAT_GOLD, "diamond": MAT_DIAMOND, "netherite": MAT_NETHERITE}
# Every kind that exists in all seven _TOOL_TIERS. The spear is one of them (wooden..netherite, same
# as a sword): it was missing here, so nothing gated it — the mod let any spear be crafted and picked
# up freely, and acquire() never asked for the Knowledge, even though Spear Handling ships as a
# progression item and can_kill() counts it as a real melee weapon.
_TOOL_KINDS = {"sword": K_SWORD, "pickaxe": K_PICKAXE, "axe": K_AXE, "shovel": K_SHOVEL,
               "hoe": K_HOE, "spear": K_SPEAR}
_ARMOR_TIERS = {"leather": MAT_WOOD, "chainmail": MAT_IRON, "copper": MAT_COPPER, "iron": MAT_IRON,
                "golden": MAT_GOLD, "diamond": MAT_DIAMOND, "netherite": MAT_NETHERITE}
_ARMOR_PIECES = ["helmet", "chestplate", "leggings", "boots"]

TOOL_LOCKS: dict[str, tuple[str, int]] = {}
for _mat, _tier in _TOOL_TIERS.items():
    for _kind, _knowledge in _TOOL_KINDS.items():
        TOOL_LOCKS[f"{_mat}_{_kind}"] = (_knowledge, _tier)
for _mat, _tier in _ARMOR_TIERS.items():
    for _piece in _ARMOR_PIECES:
        TOOL_LOCKS[f"{_mat}_{_piece}"] = (K_ARMOR, _tier)
TOOL_LOCKS.update({
    "turtle_helmet" : (K_ARMOR, MAT_WOOD),
    "bow"           : (K_BOW, MAT_WOOD),
    "crossbow"      : (K_BOW, MAT_WOOD),
    "trident"       : (K_TRIDENT, MAT_WOOD),
    "mace"          : (K_MACE, MAT_WOOD),
    "shears"        : (K_SHEAR, MAT_IRON),
    "brush"         : (K_BRUSH, MAT_COPPER),
    "fishing_rod"   : (K_FISHING, MAT_WOOD),
    "shield"        : (K_SHIELD, MAT_IRON),
    "flint_and_steel": (K_PYRO, MAT_IRON),
    "fire_charge"   : (K_PYRO, MAT_WOOD),  # blaze powder + coal + gunpowder (no metal)
    "elytra"        : (K_FLYING, MAT_WOOD),
    # Crafting stations: gated like tools so they can't be crafted/picked up without the Knowledge
    # (plus their recipe's material tier). Their *use* is additionally gated below.
    "enchanting_table": (K_ENCHANT, MAT_DIAMOND),  # 4 obsidian + 2 diamond + book
    "brewing_stand"   : (K_BREWING, MAT_STONE),    # blaze rod + 3 cobblestone
})

# The Overworld biomes you can count on walking into. Anything that can't be found in one of them is
# biome-bound: obtaining it means finding its biome, which is what the Biome Finder is for. That holds
# for a block that only generates there (cactus: desert and badlands) and a mob that only spawns there
# (husk, camel, fox, goat, mooshroom…). It used to be the other way round, a short hand list of RARE
# biomes, and everything else counted as "you'll trip over it": a desert was free, so cactus was.
#
# Overworld only. A Nether or End block is gated by reaching that dimension, and a thing that also
# occurs outside the Overworld is not bound to an Overworld biome.
COMMON_BIOMES: frozenset[str] = frozenset({
    "plains", "forest", "birch_forest", "river", "ocean", "beach",
})
_OUTSIDE_OVERWORLD: frozenset[str] = frozenset({
    "nether_wastes", "crimson_forest", "warped_forest", "soul_sand_valley", "basalt_deltas",
    "the_end", "end_barrens", "end_highlands", "end_midlands", "small_end_islands", "the_void",
})


def biome_bound(biomes) -> bool:
    """Whether something found only in ``biomes`` needs the Biome Finder (see COMMON_BIOMES). An empty
    list means "not biome worldgen at all" and is never bound."""
    biomes = frozenset(biomes or ())
    return bool(biomes) and not biomes & (COMMON_BIOMES | _OUTSIDE_OVERWORLD)


# -----------------------------------------------------------------------------------------------
# Per Minecraft version
# -----------------------------------------------------------------------------------------------

class MinecraftContent:
    """Everything that depends on the Minecraft version a player chose: the pack-derived records and
    the tables built from them. Attribute names match the module-level names they replaced, so a rule
    reads ``world.content.MOBS_ALL`` where it used to read ``MOBS_ALL``.

    Built in two steps (see _build_all): the raw records first, with each version's own positional
    ids, then ``_finish`` once the ids have been replaced by the union ones — the kill locations and
    every derived table come from the final ids."""

    def __init__(self, version: str):
        self.version = version
        self.BASE_PACK: str = base_pack(version)
        registry = load_pack(self.BASE_PACK)
        self.MOBS_ALL: dict[str, MCMobData] = registry.mobs
        self.LOCATIONS_ADVANCEMENT: dict[str, MCLocationData] = registry.advancements

        self.OVERLAY_PACKS: list[tuple[str, str]] = [
            (pack_name, OVERLAY_OPTIONS[namespace])
            for namespace, pack_name in overlay_packs(version).items()
            if namespace in OVERLAY_OPTIONS
        ]
        # The discovered BACAP pack for this version (None if none is installed for it).
        self.BACAP_PACK: str | None = overlay_packs(version).get("blazeandcave")

        # Per-overlay content REQUIREMENTS the mod must verify on world load. When an overlay's option
        # is on, the seed depends on that datapack/mod being installed at the matching version, so the
        # mod refuses to load a world missing it (see the mod's ContentVerification /
        # ArchipelagoConnectingScreen). Each entry is (enabling option, requirement dict);
        # fill_slot_data emits the dicts whose option is on this seed.
        #   kind    : "datapack" | "mod" — how the mod locates it (pack repository vs Fabric loader).
        #   id      : stable identifier (the pack namespace / the Fabric mod id).
        #   name    : human display name for the "please install X" message.
        #   version : the version this apworld was generated against (must match what's installed).
        #   match   : lowercase substring identifying the pack among installed datapacks (datapack kind
        #             only; BACAP ships its version only in its datapack filename, so the mod matches on
        #             that).
        self.OVERLAY_REQUIREMENTS: list[tuple[str, dict]] = []
        for namespace, pack_name in overlay_packs(version).items():
            if namespace not in OVERLAY_OPTIONS:
                continue
            meta = pack_meta(pack_name)
            self.OVERLAY_REQUIREMENTS.append((OVERLAY_OPTIONS[namespace], {
                "kind"   : "mod" if meta.get("source") == "mod" else "datapack",
                "id"     : namespace,
                "name"   : meta.get("display_name", namespace),
                "version": str(meta.get("content_version", "")),
                "match"  : str(meta.get("match", namespace)).lower(),
            }))

        # STRUCTURES is the vanilla base plus every overlay pack's worldgen structures (e.g. a
        # datapack/mod that generates new structures), so each becomes a Structure Unlock item and its
        # palette feeds the acquisition logic. Keyed by game_id (the stable identifier); an overlay
        # never shadows a base structure. STRUCTURE_PACK_OPTION maps an overlay structure (by game_id)
        # to the option that must be on for it to exist this seed (vanilla structures are absent here
        # = always active).
        self.STRUCTURES: dict[str, MCStructureData] = dict(registry.structures)
        self.STRUCTURE_PACK_OPTION: dict[str, str] = {}
        next_struct_id = max((s.id for s in self.STRUCTURES.values()), default=-1) + 1
        for overlay_pack, overlay_option in self.OVERLAY_PACKS:
            overlay_structs = load_structures(overlay_pack, id_start=next_struct_id)
            for struct_gid, struct_data in overlay_structs.items():
                if struct_gid in self.STRUCTURES:
                    continue  # an overlay never shadows a base (vanilla) structure
                self.STRUCTURES[struct_gid] = struct_data
                self.STRUCTURE_PACK_OPTION[struct_gid] = overlay_option
            if overlay_structs:
                next_struct_id = max(s.id for s in overlay_structs.values()) + 1

        # BACAP (BlazeandCave's Advancements Pack): a manifest-only datapack of custom advancements,
        # loaded unconditionally so its location ids are stable in the data package; the
        # `blazeandcave` option gates whether they are created this seed. Only the new `blazeandcave:`
        # advancements become locations here — BACAP's `minecraft:` files REWRITE the vanilla
        # advancements, so they reuse the vanilla locations (their modified criteria come from the
        # BACAP manifest in set_rules when on). Two BACAP tabs are dropped: `statistics` auto-grants
        # from scoreboard counters and `technical` is hidden datapack plumbing — neither is a real,
        # player-earnable check. Every `frame=challenge` advancement (BACAP scatters them across most
        # tabs, not just the "Super Challenges" `challenges` tab) is flagged challenge=True so the
        # challenge_sanity option gates them like vanilla's.
        self._vanilla_advancement_game_ids = frozenset(loc.game_id for loc in self.LOCATIONS_ADVANCEMENT.values())
        reserved = frozenset(self.LOCATIONS_ADVANCEMENT) | frozenset(
            f"{prefix}{name}" for name in self.MOBS_ALL for prefix in (ENTITY_KILL_PREFIX, BOSS_KILL_PREFIX))
        self.LOCATIONS_BACAP: dict[str, MCLocationData] = load_manifest_advancements(
            self.BACAP_PACK, BASE_ID_LOC_BACAP, reserved=reserved,
            skip_game_ids=self._vanilla_advancement_game_ids,
            skip_tabs=frozenset({"statistics", "technical"}),
            challenge_tabs=frozenset({"challenges"}),
            # The overview `bacap` tab's goal/challenge entries (per-tab Milestones, Advancement
            # Legend) are aggregate markers earned by completing other advancements, not checks; its
            # `task` entries stay.
            skip_frame_tabs={"bacap": frozenset({"goal", "challenge"})}) if self.BACAP_PACK else {}

    def _finish(self) -> None:
        """Every table derived from the (union-id) records."""
        mobs = self.MOBS_ALL
        # Reverse of the cosmetic display label -> game_id, for the user-facing edges (the
        # structure_unlock YAML option and parsing a "Structure Unlock: <label>" item name back to its
        # structure).
        self.STRUCTURE_BY_LABEL: dict[str, str] = {data.label: gid for gid, data in self.STRUCTURES.items()}

        # Every station/container block that has a Knowledge gate: full block id -> bare knowledge
        # name. Built from the dumped containers.json (see registry.knowledge_groups), so a block only
        # appears here if this version's game actually has it. Both halves of a station/container gate
        # read this: the mod blocks right-clicking the block (station_knowledge_locks ->
        # KnowledgeUseGate) and blocks crafting/picking it up (tool_locks), and the logic requires the
        # knowledge to acquire the block item.
        #
        # Whether a gate is ON is a per-seed option (knowledge_gates), resolved in
        # MCWorld._active_knowledges; this map is the full catalogue, unfiltered.
        containers = load_containers(self.BASE_PACK)
        block_knowledge: dict[str, str] = {}
        for group, blocks in knowledge_groups(containers).items():
            for block_id in blocks:
                block_knowledge[block_id] = gate_knowledge_name(group)
        # Only gates that actually exist as rows in knowledges.csv can be required (a dump can run
        # ahead of the CSV; tools/build_knowledges.py is what adds the rows).
        self.BLOCK_KNOWLEDGE: dict[str, str] = {
            block: name for block, name in block_knowledge.items() if name in KNOWLEDGES}

        # Recipe station key (as it appears in acquisition.json's ``station``) -> the knowledges that
        # can run it, ORed: "crafting" is satisfied by a Crafting Table OR a Crafter. Derived from the
        # dump's recipe_station field, which is how a smelting recipe learns it needs the Furnace gate.
        self.RECIPE_STATION_KNOWLEDGE: dict[str, list[str]] = {}
        # The same key -> the BLOCKS that run it ("smelting" -> the furnace). The Knowledge above is
        # only the permission to use one; this is the block itself, which a recipe needs whether or not
        # a gate is on.
        self.RECIPE_STATION_BLOCKS: dict[str, list[str]] = {}
        for record in containers:
            station = record.get("recipe_station")
            if not station:
                continue
            knowledge = self.BLOCK_KNOWLEDGE.get(record["block"])
            if knowledge and knowledge not in self.RECIPE_STATION_KNOWLEDGE.setdefault(station, []):
                self.RECIPE_STATION_KNOWLEDGE[station].append(knowledge)
            if record["block"] not in self.RECIPE_STATION_BLOCKS.setdefault(station, []):
                self.RECIPE_STATION_BLOCKS[station].append(record["block"])

        # Legacy alias: the two stations gated before the option existed. STATION_KNOWLEDGE_LOCKS is
        # now the whole BLOCK_KNOWLEDGE catalogue (keyed by full id), kept under its old name for
        # fill_slot_data.
        self.STATION_KNOWLEDGE_LOCKS: dict[str, str] = dict(self.BLOCK_KNOWLEDGE)

        # Mobs that spawn in no common biome (biome_bound), so obtaining one IS finding its biome.
        # Derived from EntitiesDump's `biomes`; a mob with no biomes at all (a boss, a creaking, a
        # sniffer) is not claimed either way here — acquisition.py adds those few explicitly, since what
        # they cost is not a biome search. Empty on a pack dumped before the field existed, which is why
        # the explicit entries there are a fallback rather than an override.
        self.BIOME_BOUND_MOBS: frozenset[str] = frozenset(
            name for name, mob in mobs.items()
            if biome_bound(mob.biomes)
        )

        self.MOBS_PASSIVE = {k: v for k, v in mobs.items() if v.category == MCEntityCategory.PASSIVE}
        self.MOBS_NEUTRAL = {k: v for k, v in mobs.items() if v.category == MCEntityCategory.NEUTRAL}
        self.MOBS_HOSTILE = {k: v for k, v in mobs.items() if v.category == MCEntityCategory.HOSTILE}
        self.MOBS_BOSS = {k: v for k, v in mobs.items() if v.category == MCEntityCategory.BOSS}
        self.MOBS_BREEDABLE = {k: v for k, v in mobs.items() if v.breedable}
        self.MOBS_TAMEABLE = {k: v for k, v in mobs.items() if v.tameable}
        self.MOBS_LEASHABLE = {k: v for k, v in mobs.items() if v.leashable}

        self.LOCATIONS_MOB_KILL: dict[str, MCLocationData] = _load_mob_kill_locations(mobs)
        self.LOCATIONS_BOSS_KILL: dict[str, MCLocationData] = _load_boss_kill_locations(mobs)
        self.ALL_LOCATIONS: dict[str, MCLocationData] = {
            **self.LOCATIONS_ADVANCEMENT,
            **self.LOCATIONS_MOB_KILL,
            **self.LOCATIONS_BOSS_KILL,
        }
        # The vanilla+BACAP union the trigger compiler walks (parent-chain lookup).
        self.ADVANCEMENT_LOCATIONS: dict[str, MCLocationData] = {**self.LOCATIONS_ADVANCEMENT, **self.LOCATIONS_BACAP}

        # Vanilla advancement locations BACAP *rewrites* (its `minecraft:` files reuse them). BACAP can
        # give the advancement a different frame than vanilla, promoting a goal/task to a challenge or
        # demoting a challenge, so when blazeandcave is on the challenge_sanity gate must follow BACAP's
        # frame, not the vanilla flag baked into the location. Maps rewritten game_id -> BACAP challenge.
        bacap_challenge: dict[str, bool] = load_manifest_challenge(self.BACAP_PACK) if self.BACAP_PACK else {}
        self.BACAP_REWRITE_CHALLENGE: dict[str, bool] = {
            game_id: bacap_challenge[game_id]
            for game_id in self._vanilla_advancement_game_ids if game_id in bacap_challenge
        }

        # Vanilla advancements BACAP DELETES outright (its rewrite strips the display — see
        # load_manifest_removed). While blazeandcave is on these cannot be earned at all, so they must
        # not be created as checks: "Serious Dedication" (vanilla's netherite hoe) is the one, and BACAP
        # replaces it with a same-titled advancement of its own under a different id.
        self.BACAP_REMOVED: frozenset[str] = (
            load_manifest_removed(self.BACAP_PACK) & self._vanilla_advancement_game_ids
        ) if self.BACAP_PACK else frozenset()


def _canonical_ids(tables: list[dict[str, int]]) -> dict[str, int]:
    """One id per name across versions (oldest first). Every name of the OLDEST version keeps exactly
    the id that version gives it, so adding a version never moves an id a released seed used; a name
    that first appears in a later version takes the next free id after everything already assigned,
    in that version's own order. Stable for as long as versions are only ever ADDED after the newest —
    a pack for an older release slotted in front would renumber the ones after it."""
    ids: dict[str, int] = dict(tables[0]) if tables else {}
    for table in tables[1:]:
        next_id = max(ids.values(), default=-1) + 1
        for name, _ in sorted(table.items(), key=lambda entry: entry[1]):
            if name not in ids:
                ids[name] = next_id
                next_id += 1
    return ids


def _remap(records: dict, ids: dict[str, int]) -> dict:
    return {key: dataclasses.replace(record, id=ids[key]) for key, record in records.items()}


def _build_all() -> dict[str, MinecraftContent]:
    versions = minecraft_versions()
    contents = {version: MinecraftContent(version) for version in versions}
    ordered = [contents[version] for version in versions]
    for attribute in ("MOBS_ALL", "STRUCTURES", "LOCATIONS_ADVANCEMENT", "LOCATIONS_BACAP"):
        ids = _canonical_ids([{key: record.id for key, record in getattr(c, attribute).items()} for c in ordered])
        for content in ordered:
            setattr(content, attribute, _remap(getattr(content, attribute), ids))
    for content in ordered:
        content._finish()
    return contents


# Every playable Minecraft version, oldest first, and its content.
MINECRAFT_VERSIONS: list[str] = minecraft_versions()
_CONTENT: dict[str, MinecraftContent] = _build_all()


def content_for(version: str) -> MinecraftContent:
    """The content of one Minecraft version (a ``minecraft_version`` option value)."""
    return _CONTENT[version]


# -----------------------------------------------------------------------------------------------
# The static AP data package: every version's names, one id each
# -----------------------------------------------------------------------------------------------

def _union(table: str) -> dict:
    """``{key: record}`` over every version (records for a shared key carry the same union id)."""
    merged: dict = {}
    for version in MINECRAFT_VERSIONS:
        for key, record in getattr(_CONTENT[version], table).items():
            merged.setdefault(key, record)
    return merged


# Every mob / structure / boss / advancement any version has: what the options offer and the data
# package names. A player whose version lacks one simply doesn't get it (see MCWorld's option resolvers).
ALL_VERSIONS_MOBS: dict[str, MCMobData] = _union("MOBS_ALL")
ALL_VERSIONS_BOSSES: dict[str, MCMobData] = _union("MOBS_BOSS")
ALL_VERSIONS_STRUCTURES: dict[str, MCStructureData] = _union("STRUCTURES")
ALL_VERSIONS_ADVANCEMENTS: dict[str, MCLocationData] = _union("ADVANCEMENT_LOCATIONS")

ITEM_NAME_TO_ID: dict[str, int] = {
    **{name: data.id for (name, data) in ITEMS.items()},
    **{f"{ENTITY_UNLOCK_PREFIX}{name}": BASE_ID_ENTITY_UNLOCK + mob.id for (name, mob) in ALL_VERSIONS_MOBS.items()},
    **{f"{STRUCT_UNLOCK_PREFIX}{structure.label}": BASE_ID_STRUCT_UNLOCK + structure.id
       for structure in ALL_VERSIONS_STRUCTURES.values()},
}

# Advancement and kill locations of every version; BACAP's blazeandcave locations too (always in the
# static data package, created only when the blazeandcave option is on — its minecraft rewrites reuse
# the vanilla locations).
LOCATION_NAME_TO_ID: dict[str, int] = {}
for _version in MINECRAFT_VERSIONS:
    for _table in ("ALL_LOCATIONS", "LOCATIONS_BACAP"):
        for _name, _location in getattr(_CONTENT[_version], _table).items():
            _known = LOCATION_NAME_TO_ID.setdefault(_name, _location.id)
            if _known != _location.id:
                raise ValueError(f"AEMinecraft: location {_name!r} has id {_known} in one Minecraft version "
                                 f"and {_location.id} in {_version}; ids must be version-independent.")


def _check_unique_ids(table: dict[str, int], what: str) -> None:
    by_id: dict[int, str] = {}
    for name, item_id in table.items():
        if by_id.setdefault(item_id, name) != name:
            raise ValueError(f"AEMinecraft: {what} id {item_id} is shared by {by_id[item_id]!r} and {name!r}.")


_check_unique_ids(ITEM_NAME_TO_ID, "item")
_check_unique_ids(LOCATION_NAME_TO_ID, "location")
