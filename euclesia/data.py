import csv
from dataclasses import dataclass
from importlib.resources import files
from pathlib import Path
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