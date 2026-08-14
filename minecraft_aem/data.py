"""Public data surface for the apworld.

Parsing of the content packs now lives in :mod:`content.registry`; this module loads the vanilla
pack and re-exports its records (plus the gameplay lock maps and mob subsets) under the same
module-level names the rest of the apworld already imports. ``from .data import *`` therefore still
yields ITEMS / MOBS_ALL / STRUCTURES / ALL_LOCATIONS / the BASE_ID_* / dataclasses / etc., and —
via ``from .logic.constants import *`` below — the rule constants (prefixes, ITEM_*, MAT_*, K_*).
"""
# NOTE: most names below are re-exported via `from .data import *` for the rest of the apworld;
# they look "unused" to linters here, so do not auto-strip them.
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
    base_pack,
    gate_knowledge_name,
    knowledge_groups,
    load_containers,
    load_manifest_advancements,
    load_manifest_challenge,
    load_pack,
    load_structures,
    overlay_packs,
    pack_meta,
)
from .logic.constants import *

# Vanilla is the discovered base pack (the source=="vanilla" pack whose meta.mc_version matches
# CONTENT_VERSION); manifest-only datapack/mod packs (BACAP) are loaded too so their location ids and
# the compiler's parent-chain lookup are always present (the option only gates whether those locations
# are *created* this seed — see MCWorld._get_active_locations).
_REGISTRY: ContentRegistry = load_pack(base_pack())

# The curated CSV items (progression/useful + trap filler) plus every generated **filler** — the buffs
# and the safe item stacks (dirt, tuff, boats, …), all declared in filler.py under their own id block
# (filler.BASE_ID_FILLER_ITEM). Merging them here means the world registers, creates and filler-weights
# them with no CSV upkeep; the buffs live ONLY in filler.py now (no rows in items.csv). See filler.py.
from .filler import FILLER_ITEMS  # noqa: E402  (import here to avoid a top-level cycle via content)

# Knowledge gates (content/knowledges.csv), keyed by their BARE name ("Sword Handling") — the form the
# K_* constants and TOOL_LOCKS/STATION_KNOWLEDGE_LOCKS use. KNOWLEDGES_BY_CATEGORY backs the
# knowledge_gates option's category presets (tools / armor / misc / stations / containers).
KNOWLEDGES: dict[str, MCKnowledgeData] = _REGISTRY.knowledges
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

ITEMS: dict[str, MCItemData] = {**_REGISTRY.items, **KNOWLEDGE_ITEMS, **FILLER_ITEMS}
MOBS_ALL: dict[str, MCMobData] = _REGISTRY.mobs

# Overlay packs: mod/datapack content (dumped in-game via /aem dump, then dropped into packs/)
# layered on top of the vanilla base. They are DISCOVERED by content.registry (every non-vanilla pack
# whose meta.mc_version matches CONTENT_VERSION), then wired to the option that enables them via this
# namespace -> option map. Each is loaded unconditionally so the structures/locations it adds always
# have stable ids in the (static) AP data package; the option then gates whether that content is
# actually created/active this seed — exactly how `blazeandcave` gates BACAP. To add a mod/datapack:
# dump it, drop the pack into packs/, give it an option in options.py, and add its namespace here.
OVERLAY_OPTIONS: dict[str, str] = {"blazeandcave": "blazeandcave"}  # pack namespace -> enabling option
OVERLAY_PACKS: list[tuple[str, str]] = [
    (pack_name, OVERLAY_OPTIONS[namespace])
    for namespace, pack_name in overlay_packs().items()
    if namespace in OVERLAY_OPTIONS
]

# The discovered BACAP pack for this version (None if none is installed for CONTENT_VERSION).
BACAP_PACK: str | None = overlay_packs().get("blazeandcave")

# Per-overlay content REQUIREMENTS the mod must verify on world load. When an overlay's option is on,
# the seed depends on that datapack/mod being installed at the matching version, so the mod refuses to
# load a world missing it (see the mod's ContentVerification / ArchipelagoConnectingScreen). Each entry
# is (enabling option, requirement dict); fill_slot_data emits the dicts whose option is on this seed.
#   kind    : "datapack" | "mod" — how the mod locates it (pack repository vs Fabric loader).
#   id      : stable identifier (the pack namespace / the Fabric mod id).
#   name    : human display name for the "please install X" message.
#   version : the version this apworld was generated against (must match what's installed).
#   match   : lowercase substring identifying the pack among installed datapacks (datapack kind only;
#             BACAP ships its version only in its datapack filename, so the mod matches on that).
OVERLAY_REQUIREMENTS: list[tuple[str, dict]] = []
for _namespace, _pack_name in overlay_packs().items():
    if _namespace not in OVERLAY_OPTIONS:
        continue
    _meta = pack_meta(_pack_name)
    OVERLAY_REQUIREMENTS.append((OVERLAY_OPTIONS[_namespace], {
        "kind"   : "mod" if _meta.get("source") == "mod" else "datapack",
        "id"     : _namespace,
        "name"   : _meta.get("display_name", _namespace),
        "version": str(_meta.get("content_version", "")),
        "match"  : str(_meta.get("match", _namespace)).lower(),
    }))

# STRUCTURES is the vanilla base plus every overlay pack's worldgen structures (e.g. a datapack/mod
# that generates new structures), so each becomes a Structure Unlock item and its palette feeds the
# acquisition logic. Keyed by game_id (the stable identifier); ids continue past vanilla's so they
# stay stable as packs are added; an overlay never shadows a base structure. STRUCTURE_PACK_OPTION
# maps an overlay structure (by game_id) to the option that must be on for it to exist this seed
# (vanilla structures are absent here = always active).
STRUCTURES: dict[str, MCStructureData] = dict(_REGISTRY.structures)
STRUCTURE_PACK_OPTION: dict[str, str] = {}
_next_struct_id = max((s.id for s in STRUCTURES.values()), default=-1) + 1
for _overlay_pack, _overlay_option in OVERLAY_PACKS:
    _overlay_structs = load_structures(_overlay_pack, id_start=_next_struct_id)
    for _struct_gid, _struct_data in _overlay_structs.items():
        if _struct_gid in STRUCTURES:
            continue  # an overlay never shadows a base (vanilla) structure
        STRUCTURES[_struct_gid] = _struct_data
        STRUCTURE_PACK_OPTION[_struct_gid] = _overlay_option
    if _overlay_structs:
        _next_struct_id = max(s.id for s in _overlay_structs.values()) + 1

# Reverse of the cosmetic display label -> game_id, for the user-facing edges (the structure_unlock
# YAML option and parsing a "Structure Unlock: <label>" item name back to its structure).
STRUCTURE_BY_LABEL: dict[str, str] = {data.label: gid for gid, data in STRUCTURES.items()}

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

# Every station/container block that has a Knowledge gate: full block id -> bare knowledge name. Built
# from the dumped containers.json (see registry.knowledge_groups), so a block only appears here if the
# running game actually has it. Both halves of a station/container gate read this: the mod blocks
# right-clicking the block (station_knowledge_locks -> KnowledgeUseGate) and blocks crafting/picking it
# up (tool_locks), and the logic requires the knowledge to acquire the block item.
#
# Whether a gate is ON is a per-seed option (knowledge_gates), resolved in MCWorld._active_knowledges;
# this map is the full catalogue, unfiltered.
BLOCK_KNOWLEDGE: dict[str, str] = {}
for _group, _blocks in knowledge_groups(load_containers(base_pack())).items():
    for _block_id in _blocks:
        BLOCK_KNOWLEDGE[_block_id] = gate_knowledge_name(_group)
# Only gates that actually exist as rows in knowledges.csv can be required (a dump can run ahead of the
# CSV; tools/build_knowledges.py is what adds the rows).
BLOCK_KNOWLEDGE = {block: name for block, name in BLOCK_KNOWLEDGE.items() if name in KNOWLEDGES}

# Recipe station key (as it appears in acquisition.json's ``station``) -> the knowledges that can run
# it, ORed: "crafting" is satisfied by a Crafting Table OR a Crafter. Derived from the dump's
# recipe_station field, which is how a smelting recipe learns it needs the Furnace gate.
RECIPE_STATION_KNOWLEDGE: dict[str, list[str]] = {}
for _record in load_containers(base_pack()):
    _station = _record.get("recipe_station")
    _knowledge = BLOCK_KNOWLEDGE.get(_record["block"])
    if _station and _knowledge and _knowledge not in RECIPE_STATION_KNOWLEDGE.setdefault(_station, []):
        RECIPE_STATION_KNOWLEDGE[_station].append(_knowledge)

# Legacy alias: the two stations gated before the option existed. STATION_KNOWLEDGE_LOCKS is now the
# whole BLOCK_KNOWLEDGE catalogue (keyed by full id), kept under its old name for fill_slot_data.
STATION_KNOWLEDGE_LOCKS: dict[str, str] = dict(BLOCK_KNOWLEDGE)

MOBS_PASSIVE  = {k: v for k, v in MOBS_ALL.items() if v.category == MCEntityCategory.PASSIVE}
MOBS_NEUTRAL  = {k: v for k, v in MOBS_ALL.items() if v.category == MCEntityCategory.NEUTRAL}
MOBS_HOSTILE  = {k: v for k, v in MOBS_ALL.items() if v.category == MCEntityCategory.HOSTILE}
MOBS_BOSS     = {k: v for k, v in MOBS_ALL.items() if v.category == MCEntityCategory.BOSS}
MOBS_BREEDABLE = {k: v for k, v in MOBS_ALL.items() if v.breedable}
MOBS_TAMEABLE  = {k: v for k, v in MOBS_ALL.items() if v.tameable}
MOBS_LEASHABLE = {k: v for k, v in MOBS_ALL.items() if v.leashable}

LOCATIONS_ADVANCEMENT: dict[str, MCLocationData] = _REGISTRY.advancements
LOCATIONS_MOB_KILL: dict[str, MCLocationData] = _REGISTRY.mob_kill_locations
LOCATIONS_BOSS_KILL: dict[str, MCLocationData] = _REGISTRY.boss_kill_locations

ALL_LOCATIONS: dict[str, MCLocationData] = {
    **LOCATIONS_ADVANCEMENT,
    **LOCATIONS_MOB_KILL,
    **LOCATIONS_BOSS_KILL,
}

# BACAP (BlazeandCave's Advancements Pack): a manifest-only datapack of custom advancements, loaded
# unconditionally so its location ids are stable in the data package; the `blazeandcave` option
# gates whether they are created this seed. Only the new `blazeandcave:` advancements become
# locations here — BACAP's `minecraft:` files REWRITE the vanilla advancements, so they reuse the
# vanilla locations (their modified criteria come from the BACAP manifest in set_rules when on).
# ADVANCEMENT_LOCATIONS is the vanilla+BACAP union the trigger compiler walks (parent-chain lookup).
# Two BACAP tabs are dropped: `statistics` auto-grants from scoreboard counters and `technical` is
# hidden datapack plumbing — neither is a real, player-earnable check. Every `frame=challenge`
# advancement (BACAP scatters them across most tabs, not just the "Super Challenges" `challenges`
# tab) is flagged challenge=True so the challenge_sanity option gates them like vanilla's.
_VANILLA_ADVANCEMENT_GAME_IDS = frozenset(loc.game_id for loc in LOCATIONS_ADVANCEMENT.values())
LOCATIONS_BACAP: dict[str, MCLocationData] = load_manifest_advancements(
    BACAP_PACK, BASE_ID_LOC_BACAP, reserved=frozenset(ALL_LOCATIONS),
    skip_game_ids=_VANILLA_ADVANCEMENT_GAME_IDS,
    skip_tabs=frozenset({"statistics", "technical"}),
    challenge_tabs=frozenset({"challenges"}),
    # The overview `bacap` tab's goal/challenge entries (per-tab Milestones, Advancement Legend) are
    # aggregate markers earned by completing other advancements, not checks; its `task` entries stay.
    skip_frame_tabs={"bacap": frozenset({"goal", "challenge"})}) if BACAP_PACK else {}

ADVANCEMENT_LOCATIONS: dict[str, MCLocationData] = {**LOCATIONS_ADVANCEMENT, **LOCATIONS_BACAP}

# BACAP's "Super Challenges" tab. Every entry on it is frame=challenge, so challenge_sanity's middle
# level (`frames`) has to name the tab to keep it out while still taking the challenge tiles BACAP
# scatters across mining/monsters/adventure/…
BACAP_CHALLENGES_TAB = "challenges"

# Vanilla advancement locations BACAP *rewrites* (its `minecraft:` files reuse them). BACAP can
# give the advancement a different frame than vanilla, promoting a goal/task to a challenge or
# demoting a challenge, so when blazeandcave is on the challenge_sanity gate must follow BACAP's
# frame, not the vanilla flag baked into the location. Maps rewritten game_id -> BACAP challenge.
_BACAP_CHALLENGE: dict[str, bool] = load_manifest_challenge(BACAP_PACK) if BACAP_PACK else {}
BACAP_REWRITE_CHALLENGE: dict[str, bool] = {
    game_id: _BACAP_CHALLENGE[game_id]
    for game_id in _VANILLA_ADVANCEMENT_GAME_IDS if game_id in _BACAP_CHALLENGE
}
