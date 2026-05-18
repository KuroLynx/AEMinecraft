import csv
from dataclasses import dataclass
from pathlib import Path
from BaseClasses import ItemClassification

# ---------------------------------------------------------------------------
# Base IDs
# ---------------------------------------------------------------------------

# Items
BASE_ID_ITEMS           = 0xEC0000  # Items CSV           (0xEC0000 → 0xEC00FF, 256 max)
BASE_ID_ENTITY_UNLOCK   = 0xEC0100  # Entity Unlock        (0xEC0100 → 0xEC01FF, 256 max)
BASE_ID_STRUCT_UNLOCK   = 0xEC0200  # Structure Unlock     (0xEC0200 → 0xEC02FF, 256 max)

# Locations
BASE_ID_LOC_ADVANCEMENT = 0xEC1000  # Advancements         (0xEC1000 → 0xEC10FF, 256 max)
BASE_ID_LOC_BOSS_KILL   = 0xEC1100  # Boss Kills           (0xEC1100 → 0xEC11FF, 256 max)
BASE_ID_LOC_MOB_KILL    = 0xEC1200  # Mob Kills            (0xEC1200 → 0xEC12FF, 256 max)
BASE_ID_LOC_STRUCTURE   = 0xEC1300  # Structure Locations  (0xEC1300 → 0xEC13FF, 256 max)

# Prefix for items and locations
ENTITY_UNLOCK_PREFIX    = "Entity Unlock: "
STRUCT_UNLOCK_PREFIX    = "Structure Unlock: "
ENTITY_KILL_PREFIX      = "Kill Entity: "
BOSS_KILL_PREFIX        = "Kill Boss: "
ADVANCEMENT_PREFIX      = "Advancement: "

DATA_DIR = Path(__file__).parent / "data"

# ---------------------------------------------------------------------------
# Helpers
# ---------------------------------------------------------------------------

CLASSIFICATION_MAP: dict[str, ItemClassification] = {
    "progression": ItemClassification.progression,
    "useful"     : ItemClassification.useful,
    "filler"     : ItemClassification.filler,
    "trap"       : ItemClassification.trap,
}


def _read_csv(filename: str) -> list[dict[str, str]]:
    with open(DATA_DIR / filename, encoding="utf-8") as csvfile:
        return list(csv.DictReader(csvfile))


# ---------------------------------------------------------------------------
# Items
# ---------------------------------------------------------------------------

@dataclass(frozen=True)
class MCItemData:
    id:             int
    classification: ItemClassification
    count:          int


def _load_items() -> dict[str, MCItemData]:
    items = {}
    for index, row in enumerate(_read_csv("items.csv")):
        items[row["name"]] = MCItemData(
            id             = BASE_ID_ITEMS + index,
            classification = CLASSIFICATION_MAP[row["classification"]],
            count          = int(row["count"]),
        )
    return items


ITEMS: dict[str, MCItemData] = _load_items()


# ---------------------------------------------------------------------------
# Structures
# ---------------------------------------------------------------------------

@dataclass(frozen=True)
class MCStructureData:
    id:             int
    game_id:        str
    classification: ItemClassification
    region:         str


def _load_structures() -> dict[str, MCStructureData]:
    structures = {}
    for index, row in enumerate(_read_csv("structures.csv")):
        structures[row["name"]] = MCStructureData(
            id             = index,
            game_id        = f"minecraft:{row['game_id']}",
            classification = CLASSIFICATION_MAP[row["classification"]],
            region         = row["region"],
        )
    return structures


STRUCTURES: dict[str, MCStructureData] = _load_structures()


# ---------------------------------------------------------------------------
# Mobs
# ---------------------------------------------------------------------------

class MCEntityCategory:
    PASSIVE = "passive"
    NEUTRAL = "neutral"
    HOSTILE = "hostile"
    BOSS    = "boss"


@dataclass(frozen=True)
class MCEntityData:
    id:                    int
    game_id:               str
    category:              str
    region:                str
    unlock_classification: ItemClassification
    breedable:             bool = False  # counts toward "Two by Two"
    tameable:              bool = False  # counts toward "Best Friends Forever"


def _load_mobs() -> dict[str, MCEntityData]:
    mobs = {}
    for index, row in enumerate(_read_csv("mobs.csv")):
        mobs[row["name"]] = MCEntityData(
            id                    = index,
            game_id               = "minecraft:" + row["name"].lower().replace(" ", "_"),
            category              = row["category"],
            region                = row["region"],
            unlock_classification = CLASSIFICATION_MAP[row["unlock_classification"]],
            breedable             = row["breedable"] == "True",
            tameable              = row["tameable"] == "True",
        )
    return mobs


MOBS_ALL: dict[str, MCEntityData] = _load_mobs()

# Sous-ensembles par catégorie
MOBS_PASSIVE:   dict[str, MCEntityData] = {k: v for k, v in MOBS_ALL.items() if v.category == MCEntityCategory.PASSIVE}
MOBS_NEUTRAL:   dict[str, MCEntityData] = {k: v for k, v in MOBS_ALL.items() if v.category == MCEntityCategory.NEUTRAL}
MOBS_HOSTILE:   dict[str, MCEntityData] = {k: v for k, v in MOBS_ALL.items() if v.category == MCEntityCategory.HOSTILE}
MOBS_BOSS:      dict[str, MCEntityData] = {k: v for k, v in MOBS_ALL.items() if v.category == MCEntityCategory.BOSS}
MOBS_BREEDABLE: dict[str, MCEntityData] = {k: v for k, v in MOBS_ALL.items() if v.breedable}
MOBS_TAMEABLE:  dict[str, MCEntityData] = {k: v for k, v in MOBS_ALL.items() if v.tameable}


# ---------------------------------------------------------------------------
# Locations
# ---------------------------------------------------------------------------

class MCLocationCategory:
    ADVANCEMENT = "advancement"
    MOB_KILL    = "mob_kill"
    BOSS_KILL   = "boss_kill"


@dataclass(frozen=True)
class MCLocationData:
    id:       int
    category: str
    region:   str = "Overworld"
    game_id:  str = ""


def _load_advancements() -> dict[str, MCLocationData]:
    locations = {}
    for index, row in enumerate(_read_csv("advancements.csv")):
        full_game_id = f"minecraft:{row['tab']}/{row['game_id']}"
        locations[f"{ADVANCEMENT_PREFIX}{row['name']}"] = MCLocationData(
            id       = BASE_ID_LOC_ADVANCEMENT + index,
            category = MCLocationCategory.ADVANCEMENT,
            region   = row["region"],
            game_id  = full_game_id,
        )
    return locations


def _load_mob_kill_locations() -> dict[str, MCLocationData]:
    locations = {}
    for name, mob in MOBS_ALL.items():
        if mob.category == MCEntityCategory.BOSS:
            continue
        locations[f"{ENTITY_KILL_PREFIX}{name}"] = MCLocationData(
            id       = BASE_ID_LOC_MOB_KILL + mob.id,
            category = MCLocationCategory.MOB_KILL,
            region   = mob.region,
            game_id  = mob.game_id,
        )
    return locations


def _load_boss_kill_locations() -> dict[str, MCLocationData]:
    locations = {}
    for name, mob in MOBS_BOSS.items():
        locations[f"{BOSS_KILL_PREFIX}{name}"] = MCLocationData(
            id       = BASE_ID_LOC_BOSS_KILL + mob.id,
            category = MCLocationCategory.BOSS_KILL,
            region   = mob.region,
            game_id  = mob.game_id,
        )
    return locations


LOCATIONS_ADVANCEMENT: dict[str, MCLocationData] = _load_advancements()
LOCATIONS_MOB_KILLS:   dict[str, MCLocationData] = _load_mob_kill_locations()
LOCATIONS_BOSS_KILLS:  dict[str, MCLocationData] = _load_boss_kill_locations()

ALL_LOCATIONS: dict[str, MCLocationData] = {
    **LOCATIONS_ADVANCEMENT,
    **LOCATIONS_BOSS_KILLS,
    **LOCATIONS_MOB_KILLS,
}