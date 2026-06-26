"""Build a content pack's structure registry from a Minecraft jar / datapack.

Writes ``packs/<pack>/structures.json``: one entry per structure with its game id, the region
(dimension) it generates in, and the block palette of its templates — all derived from the jar:

  * ``region``  — resolve the structure's ``biomes`` tag and intersect with ``#is_nether`` /
                  ``#is_end`` (ruined portals also carry an explicit ``placement: in_nether``).
  * ``blocks``  — every block id in the structure's NBT templates' palette(s).

``classification`` is deliberately NOT stored: a Structure Unlock is always a logic gate (so it is
at least ``progression_skip_balancing``), and whether it rises to ``progression`` depends on the
*seed* (does it gate a goal boss?), so it is computed at item creation in __init__.py.

The one curated input is the ordered ``(name, game_id)`` list below: display names aren't reliably
derivable, and the ID of a Structure Unlock item is its row index — so the order must stay stable or
existing seeds shift. New structures in a future version are added here by hand.

    [ { "name": "Ancient City", "game_id": "ancient_city", "region": "Overworld",
        "blocks": ["comparator", "deepslate", ...] }, ... ]

Usage:
    python tools/build_structures.py            # vanilla -> packs/vanilla_26_1/structures.json
    python tools/build_structures.py <jar|zip|dir> <out>
"""
import gzip
import io
import json
import os
import re
import struct
import sys
import zipfile

REPO = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))

# Curated identity + stable ID order (was structures.csv's rows). region/blocks are derived.
_STRUCTURES = [
    ("Ancient City", "ancient_city"),
    ("Bastion Remnant", "bastion_remnant"),
    ("Buried Treasure", "buried_treasure"),
    ("Desert Pyramid", "desert_pyramid"),
    ("End City", "end_city"),
    ("Nether Fortress", "fortress"),
    ("Igloo", "igloo"),
    ("Jungle Pyramid", "jungle_pyramid"),
    ("Mansion", "mansion"),
    ("Mineshaft", "mineshaft"),
    ("Mineshaft (Mesa)", "mineshaft_mesa"),
    ("Ocean Monument", "monument"),
    ("Nether Fossil", "nether_fossil"),
    ("Ocean Ruin (Cold)", "ocean_ruin_cold"),
    ("Ocean Ruin (Warm)", "ocean_ruin_warm"),
    ("Pillager Outpost", "pillager_outpost"),
    ("Ruined Portal", "ruined_portal"),
    ("Ruined Portal (Desert)", "ruined_portal_desert"),
    ("Ruined Portal (Jungle)", "ruined_portal_jungle"),
    ("Ruined Portal (Mountain)", "ruined_portal_mountain"),
    ("Ruined Portal (Nether)", "ruined_portal_nether"),
    ("Ruined Portal (Ocean)", "ruined_portal_ocean"),
    ("Ruined Portal (Swamp)", "ruined_portal_swamp"),
    ("Shipwreck", "shipwreck"),
    ("Shipwreck (Beached)", "shipwreck_beached"),
    ("Stronghold", "stronghold"),
    ("Swamp Hut", "swamp_hut"),
    ("Trail Ruins", "trail_ruins"),
    ("Trial Chambers", "trial_chambers"),
    ("Village (Desert)", "village_desert"),
    ("Village (Plains)", "village_plains"),
    ("Village (Savanna)", "village_savanna"),
    ("Village (Snowy)", "village_snowy"),
    ("Village (Taiga)", "village_taiga"),
    ("Dungeon", "monster_room"),
    ("Desert Well", "desert_well"),
]

# Structure-template top folder (data/<ns>/structure/<top>/...) -> canonical structure name(s).
# Folders with no apworld structure (the empty marker, overworld fossils) are absent. village is
# handled by its biome subfolder in _nbt_structure_names.
_NBT_STRUCTURE = {
    "ancient_city": ["Ancient City"],
    "bastion": ["Bastion Remnant"],
    "end_city": ["End City"],
    "igloo": ["Igloo"],
    "nether_fossils": ["Nether Fossil"],
    "pillager_outpost": ["Pillager Outpost"],
    "ruined_portal": ["Ruined Portal"],
    "shipwreck": ["Shipwreck", "Shipwreck (Beached)"],  # palettes don't split by variant
    "trail_ruins": ["Trail Ruins"],
    "trial_chambers": ["Trial Chambers"],
    "underwater_ruin": ["Ocean Ruin (Cold)", "Ocean Ruin (Warm)"],
    "woodland_mansion": ["Mansion"],
}
_VILLAGE_BIOMES = ["Village (Desert)", "Village (Plains)", "Village (Savanna)",
                   "Village (Snowy)", "Village (Taiga)"]


def _default_jar() -> str:
    version = "26.1.2"
    props = os.path.join(REPO, "archipelago-euclesia-minecraft", "gradle.properties")
    if os.path.exists(props):
        with open(props, encoding="utf-8") as f:
            for line in f:
                if line.startswith("minecraft_version"):
                    version = line.split("=", 1)[1].strip()
                    break
    return os.path.join(os.path.expanduser("~"), ".gradle", "caches", "fabric-loom",
                        version, "minecraft-client.jar")


def _strip_ns(value: str) -> str:
    return value.split(":", 1)[-1] if isinstance(value, str) and ":" in value else value


def _read_nbt(data: bytes):
    """Parse a (gzipped) NBT byte string into nested Python ``dict`` / ``list`` / scalars. Minimal,
    dependency-free — enough to read a structure template's block palette."""
    buf = io.BytesIO(gzip.decompress(data) if data[:2] == b"\x1f\x8b" else data)

    def u(fmt: str, size: int):
        return struct.unpack(fmt, buf.read(size))[0]

    def name() -> str:
        return buf.read(u(">H", 2)).decode("utf-8", "replace")

    def payload(tag: int):
        if tag == 1: return u(">b", 1)            # byte
        if tag == 2: return u(">h", 2)            # short
        if tag == 3: return u(">i", 4)            # int
        if tag == 4: return u(">q", 8)            # long
        if tag == 5: return u(">f", 4)            # float
        if tag == 6: return u(">d", 8)            # double
        if tag == 7: return buf.read(u(">i", 4))  # byte array
        if tag == 8: return name()                # string
        if tag == 9:                              # list
            item, count = u(">b", 1), u(">i", 4)
            return [payload(item) for _ in range(count)]
        if tag == 10:                             # compound
            out = {}
            while True:
                child = u(">b", 1)
                if child == 0:
                    return out
                key = name()  # read the name BEFORE the payload (Python evals RHS first otherwise)
                out[key] = payload(child)
        if tag == 11: return [u(">i", 4) for _ in range(u(">i", 4))]  # int array
        if tag == 12: return [u(">q", 8) for _ in range(u(">i", 4))]  # long array
        raise ValueError(f"unknown NBT tag {tag}")

    root_tag = u(">b", 1)
    name()  # root compound's (empty) name
    return payload(root_tag)


def _palette_block_names(root) -> set:
    """Block ids in a structure template's palette(s) — ``palette`` (single) or ``palettes`` (rotation
    variants). Each palette entry is a block state ``{"Name": "<id>", "Properties": {...}}``."""
    names = set()
    if not isinstance(root, dict):
        return names
    palettes = []
    if isinstance(root.get("palette"), list):
        palettes.append(root["palette"])
    if isinstance(root.get("palettes"), list):
        palettes.extend(p for p in root["palettes"] if isinstance(p, list))
    for palette in palettes:
        for state in palette:
            if isinstance(state, dict) and isinstance(state.get("Name"), str):
                names.add(_strip_ns(state["Name"]))
    return names


def _nbt_structure_names(rel: str) -> list:
    """Canonical structure name(s) a ``data/<ns>/structure/...nbt`` template belongs to."""
    m = re.search(r"/structure/(.+)\.nbt$", rel)
    if not m:
        return []
    parts = m.group(1).split("/")
    if parts[0] == "village":  # village/<biome>/... ; common & decays are shared across all
        biome = parts[1] if len(parts) > 1 else ""
        if biome in ("desert", "plains", "savanna", "snowy", "taiga"):
            return [f"Village ({biome.title()})"]
        return list(_VILLAGE_BIOMES)
    return _NBT_STRUCTURE.get(parts[0], [])


class StructureBuilder:
    def __init__(self):
        self.biome_tags: dict[str, list] = {}     # tag path (e.g. "is_nether") -> raw values
        self.struct_defs: dict[str, dict] = {}    # game_id -> worldgen/structure json
        self.palettes: dict[str, set] = {}        # canonical structure name -> palette block ids

    # -- load ---------------------------------------------------------------
    def load(self, entries):
        for name, raw in entries:
            try:
                self._dispatch(name, raw)
            except Exception:
                continue

    def _dispatch(self, name: str, raw: bytes):
        m = re.search(r"/tags/worldgen/biome/(.+)\.json$", name)
        if m:
            self.biome_tags[m.group(1)] = json.loads(raw).get("values", [])
            return
        m = re.search(r"/worldgen/structure/([^/]+)\.json$", name)
        if m and "/tags/" not in name:
            self.struct_defs[m.group(1)] = json.loads(raw)
            return
        if name.endswith(".nbt") and "/structure/" in name:
            blocks = _palette_block_names(_read_nbt(raw))
            for struct_name in _nbt_structure_names(name):
                self.palettes.setdefault(struct_name, set()).update(blocks)

    # -- region -------------------------------------------------------------
    def _biome_members(self, tag: str, seen: set | None = None) -> set:
        """Concrete biome ids in a worldgen biome tag, following nested ``#`` references."""
        seen = seen if seen is not None else set()
        tag = _strip_ns(tag)
        if tag in seen:
            return set()
        seen.add(tag)
        out = set()
        for value in self.biome_tags.get(tag, []):
            entry = value if isinstance(value, str) else value.get("id", "")
            if not entry:
                continue
            if entry.startswith("#"):
                out |= self._biome_members(entry[1:], seen)
            else:
                out.add(_strip_ns(entry))
        return out

    def _region(self, game_id: str) -> str:
        """The dimension a structure generates in, from its biome tag (Nether/End biome tags), with
        ruined portals' explicit ``placement: in_nether`` as a shortcut. Defaults to Overworld for
        feature-style structures with no worldgen definition (dungeon, desert well)."""
        struct = self.struct_defs.get(game_id)
        if struct is None:
            return "Overworld"
        for setup in struct.get("setups", []) or []:
            if setup.get("placement") == "in_nether":
                return "Nether"
        biomes = struct.get("biomes")
        members = set()
        if isinstance(biomes, str):
            members = self._biome_members(biomes[1:]) if biomes.startswith("#") else {_strip_ns(biomes)}
        elif isinstance(biomes, list):
            members = {_strip_ns(b["id"] if isinstance(b, dict) else b) for b in biomes}
        if members & self._biome_members("is_nether"):
            return "Nether"
        if members & self._biome_members("is_end"):
            return "The End"
        return "Overworld"

    # -- emit ---------------------------------------------------------------
    def table(self) -> list:
        out = []
        for name, game_id in _STRUCTURES:
            out.append({
                "name": name,
                "game_id": game_id,
                "region": self._region(game_id),
                "blocks": sorted(self.palettes.get(name, ())),
            })
        return out


def _entries(source: str):
    if os.path.isdir(source):
        for root, _dirs, files in os.walk(source):
            for fn in files:
                full = os.path.join(root, fn)
                arc = os.path.relpath(full, source).replace(os.sep, "/")
                with open(full, "rb") as f:
                    yield arc, f.read()
    else:
        with zipfile.ZipFile(source) as zf:
            for name in zf.namelist():
                if not name.endswith("/"):
                    yield name, zf.read(name)


def main() -> int:
    source = sys.argv[1] if len(sys.argv) > 1 else _default_jar()
    out = sys.argv[2] if len(sys.argv) > 2 else os.path.join(
        REPO, "minecraft", "packs", "vanilla_26_1", "structures.json")
    if not os.path.exists(source):
        print(f"source not found: {source}")
        print("usage: python tools/build_structures.py <jar|zip|dir> <out.json>")
        return 2
    builder = StructureBuilder()
    builder.load(_entries(source))
    table = builder.table()
    with open(out, "w", encoding="utf-8") as f:
        json.dump(table, f, indent=2, ensure_ascii=False)
        f.write("\n")
    by_region: dict[str, int] = {}
    palettes = 0
    for row in table:
        by_region[row["region"]] = by_region.get(row["region"], 0) + 1
        palettes += 1 if row["blocks"] else 0
    print(f"wrote {len(table)} structures to {out}")
    print("  regions:", by_region, "| with palette:", palettes)
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
