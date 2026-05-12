import csv
from dataclasses import dataclass
from pathlib import Path
from BaseClasses import ItemClassification

# ---------------------------------------------------------------------------
# Base IDs
# ---------------------------------------------------------------------------

BASE_ID_ITEMS = 0xEC0000
BASE_ID_LOCATIONS = 0xEC1000

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
    with open(DATA_DIR / filename, encoding = "utf-8") as csvfile:
        return list(csv.DictReader(csvfile))


# ---------------------------------------------------------------------------
# Items
# ---------------------------------------------------------------------------

@dataclass(frozen = True)
class ItemData:
    id: int
    classification: ItemClassification
    count: int


def _load_items() -> dict[str, ItemData]:
    items = {}
    for row in _read_csv("items.csv"):
        items[row["name"]] = ItemData(
            id = BASE_ID_ITEMS + int(row["id"]),
            classification = CLASSIFICATION_MAP[row["classification"]],
            count = int(row["count"]),
        )
    return items


ITEMS: dict[str, ItemData] = _load_items()


# ---------------------------------------------------------------------------
# Mobs
# ---------------------------------------------------------------------------

class MobCategory:
    PASSIVE = "passive"
    NEUTRAL = "neutral"
    HOSTILE = "hostile"
    BOSS = "boss"


@dataclass(frozen = True)
class MobData:
    id: int
    game_id: str
    category: str
    region: str


def _load_mobs() -> dict[str, MobData]:
    mobs = {}
    for row in _read_csv("mobs.csv"):
        mobs[row["name"]] = MobData(
            id = int(row["id"]),
            game_id = "minecraft:" + str(row["name"]).lower().replace(" ", "_"),
            category = row["category"],
            region = row["region"],
        )
    return mobs


MOBS_ALL: dict[str, MobData] = _load_mobs()

# Sous-ensembles par catégorie
MOBS_PASSIVE: dict[str, MobData] = {k: v for k, v in MOBS_ALL.items() if v.category == MobCategory.PASSIVE}
MOBS_NEUTRAL: dict[str, MobData] = {k: v for k, v in MOBS_ALL.items() if v.category == MobCategory.NEUTRAL}
MOBS_HOSTILE: dict[str, MobData] = {k: v for k, v in MOBS_ALL.items() if v.category == MobCategory.HOSTILE}
MOBS_BOSS: dict[str, MobData] = {k: v for k, v in MOBS_ALL.items() if v.category == MobCategory.BOSS}


# ---------------------------------------------------------------------------
# Locations
# ---------------------------------------------------------------------------

class LocationCategory:
    ADVANCEMENT = "advancement"
    MOB_KILL = "mob_kill"
    BOSS_KILL = "boss_kill"


@dataclass(frozen = True)
class LocationData:
    id: int
    category: str
    region: str = "Overworld"
    game_id: str = ""


def _load_advancements() -> dict[str, LocationData]:
    locations = {}
    for row in _read_csv("advancements.csv"):
        locations[row["location"]] = LocationData(
            id = BASE_ID_LOCATIONS + int(row["location"]),
            category = LocationCategory.ADVANCEMENT,
            region = row["region"],
            game_id = row["game_id"],
        )
    return locations


def _load_mob_kill_locations() -> dict[str, LocationData]:
    locations = {}
    for name, mob in MOBS_ALL.items():
        if mob.category == MobCategory.BOSS: continue
        locations[f"Kill Entity: {name}"] = LocationData(
            id = BASE_ID_LOCATIONS + 300 + int(mob.id),
            category = LocationCategory.MOB_KILL,
            region = mob.region,
            game_id = mob.game_id,
        )
    return locations


def _load_boss_kill_locations() -> dict[str, LocationData]:
    locations = {}
    for name, mob in MOBS_BOSS.items():
        locations[f"Kill Boss: {name}"] = LocationData(
            id = BASE_ID_LOCATIONS + 300 + int(mob.id),
            category = LocationCategory.BOSS_KILL,
            region = mob.region,
            game_id = mob.game_id,
        )
    return locations

LOCATIONS_ADVANCEMENT: dict[str, LocationData] = _load_advancements()
LOCATIONS_MOB_KILLS: dict[str, LocationData] = _load_mob_kill_locations()
LOCATIONS_BOSS_KILLS: dict[str, LocationData] = _load_boss_kill_locations()

ALL_LOCATIONS: dict[str, LocationData] = {
    **LOCATIONS_ADVANCEMENT,
    **LOCATIONS_MOB_KILLS,
    **LOCATIONS_BOSS_KILLS,
}
