"""Content registry: parses a *content pack* into typed records with stable Archipelago IDs.

A pack is a directory under ``minecraft/packs/<name>/`` holding ``items.csv``, ``mobs.csv``,
``structures.csv`` and ``advancements.csv`` (plus ``meta.json``), describing one content source —
vanilla today, mods / datapacks / other MC versions later. The dataclasses, ID scheme and loader
bodies were moved here verbatim from the old ``data.py`` so AP item/location IDs are unchanged.

``load_pack(name)`` returns a :class:`ContentRegistry` bundling the parsed records; ``data.py``
re-exports them as the module-level globals the rest of the apworld already imports.
"""
import json
from dataclasses import dataclass
from importlib.resources import files

from BaseClasses import ItemClassification

# Location-name prefixes live with the other rule constants; the loaders build AP location names
# from them. logic.constants is import-cycle-safe (it imports nothing from this package).
from ..logic.constants import ADVANCEMENT_PREFIX, BOSS_KILL_PREFIX, ENTITY_KILL_PREFIX

# Base IDs (unchanged from data.py — moving them here keeps every AP id identical).
BASE_ID_ITEMS           = 0xEC0000
BASE_ID_ENTITY_UNLOCK   = 0xEC0100
BASE_ID_STRUCT_UNLOCK   = 0xEC0200
BASE_ID_LOC_ADVANCEMENT = 0xEC1000
BASE_ID_LOC_BOSS_KILL   = 0xEC1100
BASE_ID_LOC_MOB_KILL    = 0xEC1200
BASE_ID_LOC_STRUCTURE   = 0xEC1300
BASE_ID_LOC_BACAP       = 0xEC2000  # datapack advancement locations (manifest-only packs)


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


_CLASS_MAP = {
    "progression": ItemClassification.progression,
    "progression_skip_balancing": ItemClassification.progression_skip_balancing,
    "useful": ItemClassification.useful,
    "filler": ItemClassification.filler,
    "trap": ItemClassification.trap,
}

# The minecraft package root (e.g. "worlds.minecraft"); pack dirs live under it in packs/<name>.
_MC_ROOT = __package__.rsplit(".", 1)[0]


def _pack_dir(name: str):
    return files(_MC_ROOT).joinpath("packs", name)


def _read_csv(pack_dir, filename: str):
    with pack_dir.joinpath(filename).open(mode="r", encoding="utf-8-sig") as f:
        import csv
        return list(csv.DictReader(f))


def _load_items(pack_dir) -> dict[str, MCItemData]:
    items = {}
    for index, row in enumerate(_read_csv(pack_dir, "items.csv")):
        items[row["name"]] = MCItemData(
            id=BASE_ID_ITEMS + index,
            classification=_CLASS_MAP.get(row["classification"], ItemClassification.filler),
            count=int(row["count"]),
        )
    return items


def _load_mobs(pack_dir) -> dict[str, MCMobData]:
    mobs = {}
    for index, row in enumerate(_read_csv(pack_dir, "mobs.csv")):
        mobs[row["name"]] = MCMobData(
            id=index,
            category=row["category"],
            region=row["region"],
            unlock_classification=_CLASS_MAP.get(row["unlock_classification"], ItemClassification.filler),
            breedable=row["breedable"].lower() == "true",
            tameable=row["tameable"].lower() == "true",
            game_id=f"minecraft:{row['name'].lower().replace(' ', '_')}",
        )
    return mobs


def _load_structures(pack_dir) -> dict[str, MCStructureData]:
    structs = {}
    for index, row in enumerate(_read_csv(pack_dir, "structures.csv")):
        structs[row["name"]] = MCStructureData(
            id=index,
            classification=_CLASS_MAP.get(row["classification"], ItemClassification.filler),
            region=row["region"],
            game_id=f"minecraft:{row['game_id']}",
        )
    return structs


def _load_advancements(pack_dir) -> dict[str, MCLocationData]:
    locations = {}
    for index, row in enumerate(_read_csv(pack_dir, "advancements.csv")):
        full_game_id = f"minecraft:{row['tab']}/{row['game_id']}" if row["game_id"] != "root" else f"minecraft:{row['tab']}/root"
        locations[f"{ADVANCEMENT_PREFIX}{row['name']}"] = MCLocationData(
            id=BASE_ID_LOC_ADVANCEMENT + index,
            category=MCLocationCategory.ADVANCEMENT,
            region=row["region"],
            game_id=full_game_id,
            challenge=row.get("challenge", "false").strip().lower() == "true",
        )
    return locations


def load_manifest_advancements(pack_name: str, id_base: int, region: str = "Overworld",
                               reserved: frozenset = frozenset(),
                               skip_game_ids: frozenset = frozenset()) -> dict[str, MCLocationData]:
    """Advancement locations for a manifest-only pack (a mod / datapack with no CSVs, e.g. BACAP).

    ``skip_game_ids`` are advancement ids the pack *rewrites* rather than adds (BACAP's
    ``minecraft:`` overrides reuse the existing vanilla locations), so they get no new location.

    The advancement *id* is the location's game_id (what the mod reports); the location name is the
    coherent ``Advancement: <title>`` (the datapack's literal display title), falling back to the
    unique ``Advancement: <namespace>/<path>`` when a title is missing or would collide with a
    ``reserved`` name (a vanilla location) or an earlier one. All locations share one ``region`` —
    the compiled criteria / parent-chain rule supplies the dimension gating — and get stable ids
    from ``id_base`` (the kept ids in sorted order, so the mapping is deterministic across runs)."""
    with _pack_dir(pack_name).joinpath("manifest.json").open(encoding="utf-8") as handle:
        manifest = json.load(handle)
    used = set(reserved)
    locations: dict[str, MCLocationData] = {}
    for advancement_id in sorted(manifest):
        if advancement_id in skip_game_ids:
            continue
        title = manifest[advancement_id].get("title")
        location_name = f"{ADVANCEMENT_PREFIX}{title}" if title else None
        if location_name is None or location_name in used:
            location_name = f"{ADVANCEMENT_PREFIX}{advancement_id.replace(':', '/')}"
        used.add(location_name)
        locations[location_name] = MCLocationData(
            id=id_base + len(locations),
            category=MCLocationCategory.ADVANCEMENT,
            region=region,
            game_id=advancement_id,
        )
    return locations


def _load_mob_kill_locations(mobs: dict[str, MCMobData]) -> dict[str, MCLocationData]:
    locations = {}
    for name, mob in mobs.items():
        if mob.category == MCEntityCategory.BOSS:
            continue
        locations[f"{ENTITY_KILL_PREFIX}{name}"] = MCLocationData(
            id=BASE_ID_LOC_MOB_KILL + mob.id,
            category=MCLocationCategory.MOB_KILL,
            region=mob.region,
            game_id=mob.game_id,
        )
    return locations


def _load_boss_kill_locations(mobs: dict[str, MCMobData]) -> dict[str, MCLocationData]:
    locations = {}
    for name, mob in mobs.items():
        if mob.category != MCEntityCategory.BOSS:
            continue
        locations[f"{BOSS_KILL_PREFIX}{name}"] = MCLocationData(
            id=BASE_ID_LOC_BOSS_KILL + mob.id,
            category=MCLocationCategory.BOSS_KILL,
            region=mob.region,
            game_id=mob.game_id,
        )
    return locations


@dataclass
class ContentRegistry:
    """Parsed records of one content pack (multi-pack merging arrives with the manifest work)."""
    items: dict[str, MCItemData]
    mobs: dict[str, MCMobData]
    structures: dict[str, MCStructureData]
    advancements: dict[str, MCLocationData]
    mob_kill_locations: dict[str, MCLocationData]
    boss_kill_locations: dict[str, MCLocationData]


def load_pack(name: str) -> ContentRegistry:
    pack_dir = _pack_dir(name)
    items = _load_items(pack_dir)
    mobs = _load_mobs(pack_dir)
    structures = _load_structures(pack_dir)
    return ContentRegistry(
        items=items,
        mobs=mobs,
        structures=structures,
        advancements=_load_advancements(pack_dir),
        mob_kill_locations=_load_mob_kill_locations(mobs),
        boss_kill_locations=_load_boss_kill_locations(mobs),
    )
