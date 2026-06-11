import csv
from dataclasses import dataclass
from importlib.resources import files

from BaseClasses import ItemClassification

from .rules.constants import *

# Base IDs
BASE_ID_ITEMS           = 0xEC0000
BASE_ID_ENTITY_UNLOCK   = 0xEC0100
BASE_ID_STRUCT_UNLOCK   = 0xEC0200
BASE_ID_LOC_ADVANCEMENT = 0xEC1000
BASE_ID_LOC_BOSS_KILL   = 0xEC1100
BASE_ID_LOC_MOB_KILL    = 0xEC1200
BASE_ID_LOC_STRUCTURE   = 0xEC1300

class MCLocationCategory:
    ADVANCEMENT = "advancement"
    MOB_KILL    = "mob_kill"
    BOSS_KILL   = "boss_kill"

class MCEntityCategory:
    PASSIVE = "passive"
    NEUTRAL = "neutral"
    HOSTILE = "hostile"
    BOSS    = "boss"

@dataclass
class MCItemData:
    id: int
    classification: ItemClassification
    count: int

@dataclass
class MCLocationData:
    id: int
    category: str
    region: str = "Overworld"
    game_id: str = ""
    challenge: bool = False

@dataclass
class MCMobData:
    id: int
    category: str
    region: str
    unlock_classification: ItemClassification
    breedable: bool
    tameable: bool
    game_id: str

@dataclass
class MCStructureData:
    id: int
    classification: ItemClassification
    region: str
    game_id: str

DATA_FOLDER = files(__package__).joinpath("data")

def _load_items() -> dict[str, MCItemData]:
    items = {}
    csv_path = DATA_FOLDER.joinpath("items.csv")
    with csv_path.open(mode="r", encoding="utf-8-sig") as f:
        reader = csv.DictReader(f)
        for index, row in enumerate(reader):
            class_map = {
                "progression": ItemClassification.progression,
                "progression_skip_balancing": ItemClassification.progression_skip_balancing,
                "useful": ItemClassification.useful,
                "filler": ItemClassification.filler,
                "trap": ItemClassification.trap
            }
            items[row["name"]] = MCItemData(
                id = BASE_ID_ITEMS + index,
                classification = class_map.get(row["classification"], ItemClassification.filler),
                count = int(row["count"])
            )
    return items

def _load_mobs() -> dict[str, MCMobData]:
    mobs = {}
    csv_path = DATA_FOLDER.joinpath("mobs.csv")
    with csv_path.open(mode="r", encoding="utf-8-sig") as f:
        reader = csv.DictReader(f)
        for index, row in enumerate(reader):
            class_map = {
                "progression": ItemClassification.progression,
                "progression_skip_balancing": ItemClassification.progression_skip_balancing,
                "useful": ItemClassification.useful,
                "filler": ItemClassification.filler
            }
            mobs[row["name"]] = MCMobData(
                id = index,
                category = row["category"],
                region = row["region"],
                unlock_classification = class_map.get(row["unlock_classification"], ItemClassification.filler),
                breedable = row["breedable"].lower() == "true",
                tameable = row["tameable"].lower() == "true",
                game_id = f"minecraft:{row['name'].lower().replace(' ', '_')}"
            )
    return mobs

def _load_structures() -> dict[str, MCStructureData]:
    structs = {}
    csv_path = DATA_FOLDER.joinpath("structures.csv")
    with csv_path.open(mode="r", encoding="utf-8-sig") as f:
        reader = csv.DictReader(f)
        for index, row in enumerate(reader):
            class_map = {
                "progression": ItemClassification.progression,
                "progression_skip_balancing": ItemClassification.progression_skip_balancing,
                "useful": ItemClassification.useful,
                "filler": ItemClassification.filler
            }
            structs[row["name"]] = MCStructureData(
                id = index,
                classification = class_map.get(row["classification"], ItemClassification.filler),
                region = row["region"],
                game_id = f"minecraft:{row['game_id']}"
            )
    return structs

# Global parsing execution
ITEMS: dict[str, MCItemData] = _load_items()
MOBS_ALL: dict[str, MCMobData] = _load_mobs()
STRUCTURES: dict[str, MCStructureData] = _load_structures()

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
_TOOL_TIERS = {"wooden": MAT_WOOD, "stone": MAT_STONE, "iron": MAT_IRON,
               "golden": MAT_GOLD, "diamond": MAT_DIAMOND, "netherite": MAT_NETHERITE}
_TOOL_KINDS = {"sword": K_SWORD, "pickaxe": K_PICKAXE, "axe": K_AXE, "shovel": K_SHOVEL, "hoe": K_HOE}
_ARMOR_TIERS = {"leather": MAT_WOOD, "chainmail": MAT_IRON, "iron": MAT_IRON,
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

# Crafting stations whose *use* (right-click to open the GUI) is gated behind a Knowledge item: the
# mod blocks interacting with a placed block until the Knowledge is received, so tables/stands found
# in villages/strongholds are inert too. Value is the bare knowledge name (rules.constants K_*).
STATION_KNOWLEDGE_LOCKS: dict[str, str] = {
    "enchanting_table": K_ENCHANT,
    "brewing_stand"   : K_BREWING,
}

MOBS_PASSIVE  = {k: v for k, v in MOBS_ALL.items() if v.category == MCEntityCategory.PASSIVE}
MOBS_NEUTRAL  = {k: v for k, v in MOBS_ALL.items() if v.category == MCEntityCategory.NEUTRAL}
MOBS_HOSTILE  = {k: v for k, v in MOBS_ALL.items() if v.category == MCEntityCategory.HOSTILE}
MOBS_BOSS     = {k: v for k, v in MOBS_ALL.items() if v.category == MCEntityCategory.BOSS}
MOBS_BREEDABLE = {k: v for k, v in MOBS_ALL.items() if v.breedable}
MOBS_TAMEABLE  = {k: v for k, v in MOBS_ALL.items() if v.tameable}

def _load_advancements() -> dict[str, MCLocationData]:
    locations = {}
    csv_path = DATA_FOLDER.joinpath("advancements.csv")
    with csv_path.open(mode="r", encoding="utf-8-sig") as f:
        reader = csv.DictReader(f)
        for index, row in enumerate(reader):
            full_game_id = f"minecraft:{row['tab']}/{row['game_id']}" if row["game_id"] != "root" else f"minecraft:{row['tab']}/root"
            locations[f"{ADVANCEMENT_PREFIX}{row['name']}"] = MCLocationData(
                id = BASE_ID_LOC_ADVANCEMENT + index,
                category = MCLocationCategory.ADVANCEMENT,
                region = row["region"],
                game_id = full_game_id,
                challenge = row.get("challenge", "false").strip().lower() == "true",
            )
    return locations

def _load_mob_kill_locations() -> dict[str, MCLocationData]:
    locations = {}
    for name, mob in MOBS_ALL.items():
        if mob.category == MCEntityCategory.BOSS:
            continue
        locations[f"{ENTITY_KILL_PREFIX}{name}"] = MCLocationData(
            id = BASE_ID_LOC_MOB_KILL + mob.id,
            category = MCLocationCategory.MOB_KILL,
            region = mob.region,
            game_id = mob.game_id,
        )
    return locations

def _load_boss_kill_locations() -> dict[str, MCLocationData]:
    locations = {}
    for name, mob in MOBS_BOSS.items():
        locations[f"{BOSS_KILL_PREFIX}{name}"] = MCLocationData(
            id = BASE_ID_LOC_BOSS_KILL + mob.id,
            category = MCLocationCategory.BOSS_KILL,
            region = mob.region,
            game_id = mob.game_id,
        )
    return locations

LOCATIONS_ADVANCEMENT: dict[str, MCLocationData] = _load_advancements()
LOCATIONS_MOB_KILL: dict[str, MCLocationData] = _load_mob_kill_locations()
LOCATIONS_BOSS_KILL: dict[str, MCLocationData] = _load_boss_kill_locations()

ALL_LOCATIONS: dict[str, MCLocationData] = {
    **LOCATIONS_ADVANCEMENT,
    **LOCATIONS_MOB_KILL,
    **LOCATIONS_BOSS_KILL,
}