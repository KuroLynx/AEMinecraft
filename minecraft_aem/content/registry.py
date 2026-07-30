"""Content registry: parses a *content pack* into typed records with stable Archipelago IDs.

A pack is a directory under ``minecraft_aem/packs/<name>/`` holding ``entities.json`` and
``structures.json`` plus a ``manifest.json`` (and ``meta.json``), describing one content source —
vanilla today, mods / datapacks / other MC versions later. ``entities.json`` (the mob registry) and
``structures.json`` are dumped from the running game (``/aem dump entities`` / ``structures``);
advancement *locations* come from the manifest (the data-driven source the trigger compiler also
reads), so vanilla, mods and datapacks are handled uniformly. (Item AP classifications/counts are the
one apworld-design table that is not a pack file — ``minecraft/content/items.csv``, plus its Knowledge
sibling ``knowledges.csv``.)

``load_pack(name)`` returns a :class:`ContentRegistry` bundling the parsed records; ``data.py``
re-exports them as the module-level globals the rest of the apworld already imports.
"""
import json
from dataclasses import dataclass
from importlib.resources import files

from BaseClasses import ItemClassification

# Location-name prefixes live with the other rule constants; the loaders build AP location names
# from them. logic.constants is import-cycle-safe (it imports nothing from this package).
from ..logic.constants import (
    ADVANCEMENT_PREFIX,
    BOSS_KILL_PREFIX,
    CONTENT_VERSION,
    ENTITY_KILL_PREFIX,
    KNOWLEDGE_PREFIX,
)

# Base IDs (unchanged from data.py — moving them here keeps every AP id identical).
BASE_ID_ITEMS           = 0xEC0000
BASE_ID_ENTITY_UNLOCK   = 0xEC0100
BASE_ID_STRUCT_UNLOCK   = 0xEC0200
BASE_ID_LOC_ADVANCEMENT = 0xEC1000
BASE_ID_LOC_BOSS_KILL   = 0xEC1100
BASE_ID_LOC_MOB_KILL    = 0xEC1200
BASE_ID_LOC_STRUCTURE   = 0xEC1300
BASE_ID_LOC_BACAP       = 0xEC2000  # datapack advancement locations (manifest-only packs)
# Knowledge items live in their own block (knowledges.csv), not in items.csv's: there is one row per
# gate and the station/container rows are appended as packs are dumped, so a shared block would shift
# every later item's id each time one is added. 0xEC3000 is filler.py's.
BASE_ID_KNOWLEDGE       = 0xEC4000


class MCLocationCategory:
    ADVANCEMENT = "advancement"
    MOB_KILL    = "mob_kill"
    BOSS_KILL   = "boss_kill"


class MCEntityCategory:
    PASSIVE = "passive"
    NEUTRAL = "neutral"
    HOSTILE = "hostile"
    BOSS    = "boss"


class MCKnowledgeCategory:
    """What a Knowledge gates, and the preset the ``knowledge_gates`` option groups it under.

    TOOL/ARMOR/MISC gate an *item* (its craft/pickup, via ``TOOL_LOCKS``): a sword needs Sword
    Handling, an elytra needs Flying. STATION/CONTAINER gate a *block* — both its craft/pickup and its
    use, so a locked furnace can neither be made nor opened, including ones found in a village.
    """
    TOOL      = "tool"
    ARMOR     = "armor"
    MISC      = "misc"
    STATION   = "station"
    CONTAINER = "container"


@dataclass
class MCItemData:
    id: int
    classification: ItemClassification
    count: int


@dataclass
class MCKnowledgeData:
    """One row of ``knowledges.csv``: a gate the player can switch on or off per seed.

    ``name`` is the bare knowledge ("Sword Handling"); the AP item is ``KNOWLEDGE_PREFIX + name``.
    ``category`` is an :class:`MCKnowledgeCategory` value, which is both what the knowledge gates and
    the preset ``knowledge_gates`` groups it under.
    """
    id: int
    name: str
    category: str
    classification: ItemClassification
    count: int

    @property
    def item_name(self) -> str:
        return f"{KNOWLEDGE_PREFIX}{self.name}"


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
    breedable: bool
    tameable: bool
    leashable: bool
    game_id: str
    # No unlock_classification: an Entity Unlock is always a logic gate, and whether it rises from
    # progression_skip_balancing to full progression depends on the seed (does it gate a goal boss?),
    # so it is computed at item creation — see MCWorld._mob_classification — exactly like structures.


@dataclass
class MCStructureData:
    id: int
    region: str
    game_id: str             # the mod-facing registry id ("minecraft:ancient_city"), for slot_data
    label: str = ""          # display name = prettify(game_id); cosmetic — YAML option + AP item label
    blocks: tuple = ()  # palette block ids (natural generation); see logic.acquisition
    # classification is NOT stored: a Structure Unlock is always a logic gate, and whether it rises
    # from progression_skip_balancing to progression depends on the seed (does it gate a goal boss?),
    # so it is computed at item creation — see MCWorld.create_item.


_CLASS_MAP = {
    "progression": ItemClassification.progression,
    "progression_skip_balancing": ItemClassification.progression_skip_balancing,
    "useful": ItemClassification.useful,
    "filler": ItemClassification.filler,
    "trap": ItemClassification.trap,
}

# The minecraft package root (e.g. "worlds.minecraft_aem"); pack dirs live under it in packs/<name>.
_MC_ROOT = __package__.rsplit(".", 1)[0]


def _pack_dir(name: str):
    return files(_MC_ROOT).joinpath("packs", name)


# Packs are discovered, never named by a constant: scan packs/ once and keep every pack whose
# meta.json `mc_version` equals CONTENT_VERSION. The vanilla pack (source == "vanilla") is the base;
# the rest (datapacks/mods like BACAP) are overlays, keyed by their namespace. A new MC version is
# adopted by dumping the packs (their meta.mc_version is the running game version), dropping them in
# packs/, and bumping CONTENT_VERSION — folder names don't matter.
_DISCOVERED: dict | None = None


def _discover() -> dict:
    """{namespace: (dir_name, source)} for every packs/<dir> whose meta.mc_version == CONTENT_VERSION."""
    global _DISCOVERED
    if _DISCOVERED is None:
        found: dict[str, tuple[str, str]] = {}
        for entry in sorted(files(_MC_ROOT).joinpath("packs").iterdir(), key=lambda p: p.name):
            meta = entry.joinpath("meta.json")
            if not (entry.is_dir() and meta.is_file()):
                continue
            with meta.open(encoding="utf-8") as f:
                data = json.load(f)
            if str(data.get("mc_version")) == str(CONTENT_VERSION):
                found[data.get("namespace")] = (entry.name, data.get("source"))
        _DISCOVERED = found
    return _DISCOVERED


def base_pack() -> str:
    """The vanilla base pack's directory name for CONTENT_VERSION (the one with source == "vanilla")."""
    for name, source in _discover().values():
        if source == "vanilla":
            return name
    raise FileNotFoundError(
        f"No vanilla content pack with meta.mc_version == {CONTENT_VERSION!r} found in packs/ "
        f"(dump one in-game and drop it in, or fix CONTENT_VERSION).")


def overlay_packs() -> dict[str, str]:
    """Non-vanilla packs (datapacks/mods, e.g. BACAP) for CONTENT_VERSION, as namespace -> dir name."""
    return {ns: name for ns, (name, source) in _discover().items() if source != "vanilla"}


def pack_meta(dir_name: str) -> dict:
    """The raw meta.json of the pack directory ``dir_name`` (empty dict if it has none)."""
    meta = files(_MC_ROOT).joinpath("packs", dir_name, "meta.json")
    if not meta.is_file():
        return {}
    with meta.open(encoding="utf-8") as f:
        return json.load(f)


def _read_csv(pack_dir, filename: str):
    with pack_dir.joinpath(filename).open(mode="r", encoding="utf-8-sig") as f:
        import csv
        return list(csv.DictReader(f))


def _load_items() -> dict[str, MCItemData]:
    """AP item classifications/counts live with the apworld (minecraft/content/items.csv), not in a
    content pack: they are randomizer-design decisions (a diamond is progression in any MC version),
    so they are version-independent and shared across packs. The pack still says *which* items exist
    and how to get them (acquisition.json)."""
    items = {}
    for index, row in enumerate(_read_csv(files(__package__), "items.csv")):
        items[row["name"]] = MCItemData(
            id=BASE_ID_ITEMS + index,
            classification=_CLASS_MAP.get(row["classification"], ItemClassification.filler),
            count=int(row["count"]),
        )
    return items


def _load_knowledges() -> dict[str, MCKnowledgeData]:
    """Read content/knowledges.csv — one row per Knowledge gate, keyed by the BARE name.

    Like items.csv this is apworld design data, not pack data: the AP classification/count of a gate is
    version-independent. What the station/container rows gate *is* pack data, and it comes from the
    dumped containers.json (``/aem dump containers``) — the rows here only say the gate exists, what
    category it belongs to, and how it is shuffled.

    Row ORDER is the AP item id (id = index), so rows are only ever appended: inserting one in the
    middle renumbers every gate after it.
    """
    knowledges = {}
    for index, row in enumerate(_read_csv(files(__package__), "knowledges.csv")):
        name = row["name"].strip()
        knowledges[name] = MCKnowledgeData(
            id=BASE_ID_KNOWLEDGE + index,
            name=name,
            category=row["category"].strip(),
            classification=_CLASS_MAP.get(row["classification"], ItemClassification.filler),
            count=int(row["count"]),
        )
    return knowledges


def _load_entities(pack_dir) -> dict[str, MCMobData]:
    """Read a pack's entities.json (dumped from the running game by ``/aem dump entities``), keyed by
    the display name derived from the game_id via ``_prettify`` ("minecraft:wither_skeleton" ->
    "Wither Skeleton") — the name every consumer uses (boss_list, mob_spawn_lock, kill locations,
    Entity Unlock labels). The list order is the stable Entity Unlock item id (id = index).

    Each record carries only game-derived facts (category / region / breedable / tameable / leashable);
    the unlock's AP classification is NOT here — it is derived per-seed from the goal (see
    ``MCWorld._mob_classification``), the same way structure unlock classifications are."""
    mobs = {}
    with pack_dir.joinpath("entities.json").open(encoding="utf-8") as f:
        rows = json.load(f)
    for index, row in enumerate(rows):
        game_id = row["game_id"]
        mobs[_prettify(game_id)] = MCMobData(
            id=index,
            category=row["category"],
            region=row["region"],
            breedable=bool(row["breedable"]),
            tameable=bool(row["tameable"]),
            leashable=bool(row["leashable"]),
            game_id=game_id if ":" in game_id else f"minecraft:{game_id}",
        )
    return mobs


def _prettify(game_id: str) -> str:
    """Display label from a structure game_id: 'ancient_city' -> 'Ancient City',
    'twilightforest:hollow_hill' -> 'Hollow Hill'. Purely cosmetic — the game_id is the key
    everywhere; this is only what the YAML structure_unlock option and the AP item label show."""
    bare = game_id.split(":")[-1]
    return " ".join(word[0].upper() + word[1:] for word in bare.split("_") if word)


def _struct_record(row: dict, sid: int) -> MCStructureData:
    # The dump leaves vanilla game_ids bare ("ancient_city") and namespaces mod/datapack ones
    # ("twilightforest:hollow_hill"); only bare ones get the implicit minecraft: namespace for the mod.
    game_id = row["game_id"]
    return MCStructureData(
        id=sid,
        region=row["region"],
        game_id=game_id if ":" in game_id else f"minecraft:{game_id}",
        label=_prettify(game_id),
        blocks=tuple(row.get("blocks", ())),
    )


def _load_structures(pack_dir) -> dict[str, MCStructureData]:
    """Read a pack's structures.json, keyed by game_id (the stable identifier; the display name is
    derived via prettify). The list order is the stable Structure Unlock item id (id = index)."""
    structs = {}
    with pack_dir.joinpath("structures.json").open(encoding="utf-8") as f:
        rows = json.load(f)
    for index, row in enumerate(rows):
        structs[row["game_id"]] = _struct_record(row, index)
    return structs


def load_structures(pack_name: str, id_start: int = 0) -> dict[str, MCStructureData]:
    """Read one pack's structures.json (keyed by game_id), assigning Structure-Unlock ids from
    ``id_start`` in list order. A pack with no structures.json (a manifest-only datapack like BACAP,
    or any mod that adds no worldgen structures) yields ``{}`` — the caller layers these onto the
    vanilla base, so an overlay pack contributes only the structures it actually defines."""
    path = _pack_dir(pack_name).joinpath("structures.json")
    if not path.is_file():
        return {}
    with path.open(encoding="utf-8") as f:
        rows = json.load(f)
    return {row["game_id"]: _struct_record(row, id_start + index) for index, row in enumerate(rows)}


def load_manifest_advancements(pack_name: str, id_base: int, region: str = "Overworld",
                               reserved: frozenset = frozenset(),
                               skip_game_ids: frozenset = frozenset(),
                               skip_tabs: frozenset = frozenset(),
                               challenge_tabs: frozenset = frozenset(),
                               skip_frame_tabs: dict | None = None
                               ) -> dict[str, MCLocationData]:
    """Advancement locations for a manifest-only pack (a mod / datapack with no CSVs, e.g. BACAP).

    ``skip_game_ids`` are advancement ids the pack *rewrites* rather than adds (BACAP's
    ``minecraft:`` overrides reuse the existing vanilla locations), so they get no new location.

    ``skip_tabs`` are whole advancement *tabs* that aren't real, player-earnable checks and so
    become no location at all (BACAP's ``statistics`` tab auto-grants from scoreboard counters, and
    its ``technical`` tab is hidden datapack plumbing). Every ``frame=challenge`` advancement is
    flagged ``challenge=True`` so the ``challenge_sanity`` option can gate it — BACAP scatters
    challenge tiles across most tabs (mining/monsters/adventure/…), not only its ``challenges`` /
    "Super Challenges" tab. ``challenge_tabs`` additionally flags whole tabs as challenge (kept for
    any challenge-tab advancement whose frame isn't literally ``challenge``).

    ``skip_frame_tabs`` maps a tab to the frames within it that aren't real checks — used for BACAP's
    overview ``bacap`` tab, whose ``goal`` (per-tab Milestones) and ``challenge`` (Advancement Legend)
    entries are aggregate markers earned by completing other advancements, not player-earnable on
    their own, while the same tab's ``task`` entries (Getting Wood, Time to Mine, …) stay real checks.

    The advancement *id* is the location's game_id (what the mod reports); the location name is the
    coherent ``Advancement: <title>`` (the datapack's literal display title), falling back to the
    unique ``Advancement: <namespace>/<path>`` when a title is missing or would collide with a
    ``reserved`` name (a vanilla location) or an earlier one. All locations share one ``region`` —
    the compiled criteria / parent-chain rule supplies the dimension gating — and get stable ids
    from ``id_base`` (the kept ids in sorted order, so the mapping is deterministic across runs)."""
    with _pack_dir(pack_name).joinpath("manifest.json").open(encoding="utf-8") as handle:
        manifest = json.load(handle)
    skip_frame_tabs = skip_frame_tabs or {}
    used = set(reserved)
    locations: dict[str, MCLocationData] = {}
    for advancement_id in sorted(manifest):
        if advancement_id in skip_game_ids:
            continue
        entry = manifest[advancement_id]
        tab = entry.get("tab")
        frame = entry.get("frame")
        if tab in skip_tabs or frame in skip_frame_tabs.get(tab, ()):
            continue
        title = entry.get("title")
        location_name = f"{ADVANCEMENT_PREFIX}{title}" if title else None
        if location_name is None or location_name in used:
            location_name = f"{ADVANCEMENT_PREFIX}{advancement_id.replace(':', '/')}"
        used.add(location_name)
        locations[location_name] = MCLocationData(
            id=id_base + len(locations),
            category=MCLocationCategory.ADVANCEMENT,
            region=region,
            game_id=advancement_id,
            challenge=frame == "challenge" or tab in challenge_tabs,
        )
    return locations


def load_manifest_challenge(pack_name: str) -> dict[str, bool]:
    """``{advancement_id: frame == "challenge"}`` for every advancement in the pack's manifest.

    Used to honour a datapack's *rewritten* frame on a reused vanilla location: when BACAP is on it
    may promote a vanilla ``goal``/``task`` to a ``challenge`` (or demote one), and the
    ``challenge_sanity`` gate must follow whichever manifest supplies the logic this seed."""
    with _pack_dir(pack_name).joinpath("manifest.json").open(encoding="utf-8") as handle:
        manifest = json.load(handle)
    return {advancement_id: entry.get("frame") == "challenge"
            for advancement_id, entry in manifest.items()}


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
    knowledges: dict[str, MCKnowledgeData]
    mobs: dict[str, MCMobData]
    structures: dict[str, MCStructureData]
    advancements: dict[str, MCLocationData]
    mob_kill_locations: dict[str, MCLocationData]
    boss_kill_locations: dict[str, MCLocationData]


def load_pack(name: str) -> ContentRegistry:
    pack_dir = _pack_dir(name)
    items = _load_items()
    mobs = _load_entities(pack_dir)
    structures = _load_structures(pack_dir)
    return ContentRegistry(
        items=items,
        knowledges=_load_knowledges(),
        mobs=mobs,
        structures=structures,
        # Advancements are loaded from the pack's manifest.json (the same data-driven source the
        # trigger compiler reads), not a CSV — every advancement is placed in the Overworld region
        # and its compiled rule supplies the real dimension gating via access_region (see
        # logic.acquisition). frame=challenge advancements are flagged so challenge_sanity gates.
        advancements=load_manifest_advancements(name, BASE_ID_LOC_ADVANCEMENT, region="Overworld"),
        mob_kill_locations=_load_mob_kill_locations(mobs),
        boss_kill_locations=_load_boss_kill_locations(mobs),
    )
