"""Build a content pack's block-biome table from a Minecraft jar: which biomes each block generates in.

A block that only generates in rare biomes (cocoa on jungle trees, glow berries in lush caves, mycelium
in mushroom fields) costs a Biome Finder to mine where it grows. Where a block generates is worldgen
data, read two ways:

* **Features.** ``worldgen/biome/<b>.json`` lists placed features per step; a placed feature names a
  configured feature (by id or inline), and a configured feature's config holds block states
  (``{"Name": "minecraft:cocoa"}``), nested features, and tree decorators — which place blocks by
  decorator TYPE rather than by state, so those few are mapped explicitly (``_DECORATOR_BLOCKS``).
* **Surface rules.** ``worldgen/noise_settings/<dim>.json`` places surface blocks (mycelium, podzol,
  red sand) through a rule tree whose ``biome`` conditions narrow where each block lands. A ``not``
  around a biome condition is read as no narrowing — wider, never falsely rare.

Output, one line per block, biomes bare and sorted::

    "<block>": ["jungle", "sparse_jungle"]

A block absent from the table is not placed by biome worldgen at all (it comes from structures,
crafting, mobs...). Usage:

    python tools/build_block_biomes.py                  # the loom-cached 26.1.2 jar -> minecraft_26_1_2
    python tools/build_block_biomes.py <jar> <out.json>
"""
import json
import os
import sys
import zipfile
from collections import defaultdict

REPO = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
_DEFAULT_OUT = os.path.join(REPO, "minecraft_aem", "packs", "minecraft_26_1_2", "block_biomes.json")
_DEFAULT_JAR = os.path.expanduser(
    "~/.gradle/caches/fabric-loom/minecraftMaven/net/minecraft/minecraft-common-deobf/26.1.2/"
    "minecraft-common-deobf-26.1.2.jar")

# Tree decorators place blocks that their config never spells out as a state.
_DECORATOR_BLOCKS = {
    "minecraft:cocoa": "cocoa",
    "minecraft:beehive": "bee_nest",
    "minecraft:trunk_vine": "vine",
    "minecraft:leave_vine": "vine",
    "minecraft:pale_moss": "pale_hanging_moss",
    "minecraft:creaking_heart": "creaking_heart",
}


# Feature types that place blocks in code, with no block state in their config.
_FEATURE_TYPE_BLOCKS = {
    "minecraft:bamboo": ("bamboo", "podzol"),
    "minecraft:sculk_patch": ("sculk", "sculk_vein", "sculk_catalyst", "sculk_shrieker"),
}


class Worldgen:
    """Resolves feature references. A placed feature and the configured feature it wraps often share an
    id (``trees_jungle``), so the two are cached apart: a placed feature's ``feature`` names a
    configured one, and a configured feature's nested references name placed ones."""

    def __init__(self, jar: zipfile.ZipFile):
        self.jar = jar
        self.names = set(jar.namelist())
        self._cache: dict[tuple, set] = {}
        self.silent_types: set = set()   # configured feature types that yielded no block — for review

    def _json(self, kind: str, ident: str):
        namespace, _, path = ident.rpartition(":")
        full = f"data/{namespace or 'minecraft'}/worldgen/{kind}/{path}.json"
        return json.loads(self.jar.read(full)) if full in self.names else None

    def placed(self, ref) -> set:
        if not isinstance(ref, str):
            return self._placed_body(ref)
        key = ("placed", ref)
        if key not in self._cache:
            self._cache[key] = set()  # cycle guard
            body = self._json("placed_feature", ref)
            self._cache[key] = self._placed_body(body) if body is not None else self.configured(ref)
        return self._cache[key]

    def _placed_body(self, body) -> set:
        if isinstance(body, dict) and "placement" in body:
            return self.configured(body.get("feature"))
        return self.configured(body)

    def configured(self, ref) -> set:
        if isinstance(ref, str):
            key = ("configured", ref)
            if key not in self._cache:
                self._cache[key] = set()
                body = self._json("configured_feature", ref)
                self._cache[key] = self.configured(body) if body is not None else set()
            return self._cache[key]
        found = set()
        if isinstance(ref, dict):
            kind = ref.get("type")
            found |= set(_FEATURE_TYPE_BLOCKS.get(kind, ()))
            self._walk(ref.get("config", {}), found)
            if not found and isinstance(kind, str):
                self.silent_types.add(kind)
        return found

    def feature_blocks(self, ref) -> set:
        return self.placed(ref)

    def _walk(self, node, found: set):
        if isinstance(node, dict):
            name = node.get("Name")
            if isinstance(name, str):
                found.add(name.split(":", 1)[-1])
            kind = node.get("type")
            if isinstance(kind, str) and kind in _DECORATOR_BLOCKS:
                found.add(_DECORATOR_BLOCKS[kind])
            for key, value in node.items():
                if key in ("feature", "features", "default") or key.endswith("_feature"):
                    for item in (value if isinstance(value, list) else [value]):
                        target = item.get("feature") if isinstance(item, dict) and "chance" in item else item
                        found |= self.placed(target)
                else:
                    self._walk(value, found)
        elif isinstance(node, list):
            for item in node:
                self._walk(item, found)


def _surface_blocks(rule, biomes: frozenset, all_biomes: frozenset, out: dict):
    """Walk a surface rule tree, narrowing the biome set on each ``biome`` condition."""
    if not isinstance(rule, dict):
        return
    kind = rule.get("type", "").split(":", 1)[-1]
    if kind == "block":
        name = (rule.get("result_state") or {}).get("Name", "")
        out[name.split(":", 1)[-1]] |= biomes
    elif kind == "sequence":
        for child in rule.get("sequence", []):
            _surface_blocks(child, biomes, all_biomes, out)
    elif kind == "condition":
        test = rule.get("if_true") or {}
        narrowed = biomes
        if test.get("type", "").split(":", 1)[-1] == "biome":
            narrowed = biomes & frozenset(b.split(":", 1)[-1] for b in test.get("biome_is", []))
        _surface_blocks(rule.get("then_run"), narrowed, all_biomes, out)


def build(jar_path: str) -> dict:
    jar = zipfile.ZipFile(jar_path)
    gen = Worldgen(jar)
    prefix = "data/minecraft/worldgen/biome/"
    biome_files = sorted(n for n in gen.names if n.startswith(prefix) and n.endswith(".json"))
    result: dict[str, set] = defaultdict(set)
    for path in biome_files:
        biome = path[len(prefix):-len(".json")]
        body = json.loads(jar.read(path))
        for step in body.get("features", []):
            for feature in (step if isinstance(step, list) else [step]):
                for block in gen.feature_blocks(feature):
                    result[block].add(biome)
    all_biomes = frozenset(path[len(prefix):-len(".json")] for path in biome_files)
    for dim in ("overworld", "nether", "end"):
        settings = gen._json("noise_settings", dim)
        if settings:
            _surface_blocks(settings.get("surface_rule"), all_biomes, all_biomes, result)
    if gen.silent_types:
        print("feature types that place no block this parser can see:", sorted(gen.silent_types))
    return {block: sorted(biomes) for block, biomes in sorted(result.items()) if biomes and block != "air"}


def main():
    jar_path = sys.argv[1] if len(sys.argv) > 1 else _DEFAULT_JAR
    out = sys.argv[2] if len(sys.argv) > 2 else _DEFAULT_OUT
    table = build(jar_path)
    with open(out, "w", encoding="utf-8") as handle:
        json.dump(table, handle, indent=2, sort_keys=True)
        handle.write("\n")
    print(f"{len(table)} blocks -> {out}")


if __name__ == "__main__":
    main()
