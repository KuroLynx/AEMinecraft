"""Build a content pack's item-acquisition table from a Minecraft jar / datapack.

A lightweight, self-contained re-implementation of the standalone "MC Item Acquisition Indexer"
(only the data it needs, no CLI / human output): it reads recipes, loot tables, villager trades and
breeding/taming food tags out of ``data/<ns>/`` and writes a compiler-friendly
``packs/<pack>/acquisition.json`` mapping every obtainable item to its sources. The acquisition
compiler (logic/acquisition.py) turns that into reachability AST, replacing the hand item map.

Per-item record (only non-empty keys present); ``<ing>`` is
``{"item": x} | {"tag": x} | {"any_of": [<ing>, ...]}``::

    "<item>": {
      "recipes":    [ {"station": "<id>", "ingredients": [ <ing>, ... ]}, ... ],
      "drops":      ["<mob>", ...],                    # mob loot table
      "mining":     ["<block>", ...],                  # block loot table
      "structures": ["<real structure name>", ...],    # chest / archaeology loot
      "trades":     [["<profession>", "<file>"], ...],
      "breeding":   ["<mob>", ...],                    # appears in a mob's food/tempt tag
      "gameplay":   ["<table>", ...]                   # fishing / sniffing / misc
    }

Usage:
    python tools/build_acquisition.py            # vanilla -> packs/vanilla_26_1/acquisition.json
    python tools/build_acquisition.py <jar|zip> <out>
"""
import json
import os
import re
import sys
import zipfile

REPO = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))


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


def _ingredient(spec):
    """Normalise a recipe ingredient to {"item":x} | {"tag":x} | {"any_of":[...]}."""
    if isinstance(spec, list):
        return {"any_of": [_ingredient(s) for s in spec]}
    if isinstance(spec, dict):
        for key in ("item", "id"):
            if key in spec:
                return _ingredient(spec[key])
        if "tag" in spec:
            return {"tag": _strip_ns(spec["tag"])}
        return {"item": "?"}
    text = str(spec)
    if text.startswith("#"):
        return {"tag": _strip_ns(text[1:])}
    return {"item": _strip_ns(text)}


def _recipe_ingredients(recipe: dict) -> list:
    """Distinct input ingredients of a recipe across the formats vanilla uses."""
    out, seen = [], set()

    def add(spec):
        ing = _ingredient(spec)
        key = json.dumps(ing, sort_keys=True)
        if key not in seen:
            seen.add(key)
            out.append(ing)

    if "key" in recipe:                                   # crafting_shaped
        for spec in recipe["key"].values():
            add(spec)
    for spec in recipe.get("ingredients", []):            # crafting_shapeless
        add(spec)
    # smelting / smithing / stonecutting / transmute single-input fields
    for field in ("ingredient", "base", "addition", "template", "input", "material"):
        if field in recipe:
            add(recipe[field])
    return out


class AcquisitionBuilder:
    def __init__(self):
        self.recipes: dict[str, list] = {}
        self.drops: dict[str, set] = {}
        self.mining: dict[str, set] = {}
        self.silk_mining: dict[str, set] = {}  # block self-drops only a Silk-Touch tool yields
        self.structures: dict[str, set] = {}
        self.trades: dict[str, set] = {}
        self.breeding: dict[str, set] = {}
        self.gameplay: dict[str, set] = {}
        self.item_tags: dict[str, list] = {}  # tag name -> raw values (items and #nested-tags)

    # -- loot helpers -------------------------------------------------------
    _ENCHANT_FUNCS = ("enchant_randomly", "enchant_with_levels", "set_enchantments")

    @staticmethod
    def _is_enchanted(entry: dict) -> bool:
        """True when a loot entry carries an enchant function, so it yields an *enchanted* item."""
        funcs = entry.get("functions")
        return isinstance(funcs, list) and any(
            isinstance(f, dict)
            and _strip_ns(str(f.get("function", ""))) in AcquisitionBuilder._ENCHANT_FUNCS
            for f in funcs)

    def _loot_items(self, entry) -> list:
        items = []
        if isinstance(entry, dict):
            for key in ("value", "name", "id"):
                if key in entry and isinstance(entry[key], str):
                    leaf = _strip_ns(entry[key])
                    items.append(leaf)
                    # No vanilla table names `enchanted_book`; it's a `book` carrying an enchant
                    # loot-function. Record enchanted_book too, so its loot/fishing/barter sources
                    # are captured (the enchant gate's no-Knowledge route — see logic/triggers.py).
                    if leaf == "book" and self._is_enchanted(entry):
                        items.append("enchanted_book")
                    break
            for key in ("children", "entries", "pools"):
                for sub in entry.get(key, []) if isinstance(entry.get(key), list) else []:
                    items.extend(self._loot_items(sub))
        return items

    # Chest / archaeology / spawner loot file (basename) -> canonical apworld structure name(s),
    # so acquire()'s `structure_name in STRUCTURES` check resolves. Names that match a structure
    # one-to-one go here; whole groups (bastion_*, stronghold_*, trial_chamber*, shipwreck_*,
    # village/*, underwater_ruin_*) are handled by prefix in _structures_for. spawn_bonus_chest is
    # the world-spawn bonus chest, not a structure, so it maps to nothing.
    _CHEST_STRUCTURE = {
        "abandoned_mineshaft": ["Mineshaft"],
        "buried_treasure": ["Buried Treasure"],
        "desert_pyramid": ["Desert Pyramid"],
        "desert_well": ["Desert Well"],
        "end_city_treasure": ["End City"],
        "igloo_chest": ["Igloo"],
        "jungle_temple": ["Jungle Pyramid"],
        "jungle_temple_dispenser": ["Jungle Pyramid"],
        "nether_bridge": ["Nether Fortress"],
        "pillager_outpost": ["Pillager Outpost"],
        "ruined_portal": ["Ruined Portal"],
        "simple_dungeon": ["Dungeon"],
        "woodland_mansion": ["Mansion"],
        "ocean_ruin_cold": ["Ocean Ruin (Cold)"],
        "ocean_ruin_warm": ["Ocean Ruin (Warm)"],
        "trail_ruins_common": ["Trail Ruins"],
        "trail_ruins_rare": ["Trail Ruins"],
    }
    _VILLAGE_BIOMES = ["Village (Desert)", "Village (Plains)", "Village (Savanna)",
                       "Village (Snowy)", "Village (Taiga)"]

    def _structures_for(self, rel: str) -> list:
        """Canonical structure name(s) a chest/archaeology/spawner loot table belongs to."""
        name = rel.rsplit("/", 1)[-1]
        if "trial_chamber" in rel:
            return ["Trial Chambers"]
        if name.startswith("bastion"):
            return ["Bastion Remnant"]
        if name.startswith("stronghold"):
            return ["Stronghold"]
        if name.startswith("shipwreck"):
            return ["Shipwreck"]
        if name.startswith("ancient_city"):
            return ["Ancient City"]
        if name.startswith("underwater_ruin"):
            return ["Ocean Ruin (Cold)", "Ocean Ruin (Warm)"]  # loot doesn't split by temperature
        if rel.startswith("village/"):
            for biome in ("desert", "plains", "savanna", "snowy", "taiga"):
                if biome in name:
                    return [f"Village ({biome.title()})"]
            return list(self._VILLAGE_BIOMES)  # profession building — present in every village
        return self._CHEST_STRUCTURE.get(name, [])

    # -- load ---------------------------------------------------------------
    def load(self, entries):
        for name, raw in entries:
            if not name.endswith(".json"):
                continue
            try:
                self._dispatch(name, raw)
            except Exception:
                continue

    def _dispatch(self, name: str, raw: bytes):
        if re.search(r"/recipes?/", name):
            self._recipe(json.loads(raw))
            return
        m = re.search(r"/loot_tables?/([^/]+)/(.+)\.json$", name)
        if m:
            self._loot(m.group(1), m.group(2), json.loads(raw))
            return
        m = re.search(r"/villager_trade/([^/]+)/(.+)\.json$", name)
        if m:
            self._trade(m.group(1), m.group(2), json.loads(raw))
            return
        m = re.search(r"/tags/items?/(.+)\.json$", name)
        if m:
            tag = json.loads(raw)
            self.item_tags[m.group(1)] = tag.get("values", [])
            if name.endswith("_food.json") or name.endswith("_tempt_items.json"):
                stem = os.path.basename(name)
                mob = stem.replace("_food.json", "").replace("_tempt_items.json", "")
                self._food(mob, tag)

    def _recipe(self, recipe: dict):
        result = recipe.get("result") or {}
        item = result if isinstance(result, str) else result.get("item") or result.get("id")
        if not item:
            return
        ingredients = _recipe_ingredients(recipe)
        if ingredients:
            self.recipes.setdefault(_strip_ns(item), []).append({
                "station": _strip_ns(recipe.get("type", "")),
                "ingredients": ingredients,
            })

    @staticmethod
    def _pool_needs_silk(pool: dict) -> bool:
        """True when a loot pool only rolls under a ``match_tool`` Silk-Touch condition — i.e. the
        block drops itself only when broken with a Silk-Touch tool (bee_nest, ice, glass, coral …).
        Read straight from the loot data so the gate stays data-driven, not a hand-kept block list."""
        for cond in pool.get("conditions", []):
            if _strip_ns(str(cond.get("condition", ""))) != "match_tool":
                continue
            preds = (cond.get("predicate", {}) or {}).get("predicates", {}) or {}
            enchants = preds.get("minecraft:enchantments") or preds.get("enchantments") or []
            for ench in enchants:
                if "silk_touch" in str(ench.get("enchantments", "")):
                    return True
        return False

    def _loot(self, category: str, rel: str, loot: dict):
        file_name = rel.rsplit("/", 1)[-1]
        # A block table's pools can be split by tool condition: an item the block yields ONLY from a
        # Silk-Touch pool is a Silk-Touch drop (gated behind enchanting), not a free mine.
        if category == "blocks":
            free, silk = set(), set()
            for pool in loot.get("pools", []):
                target = silk if self._pool_needs_silk(pool) else free
                for entry in pool.get("entries", []):
                    target.update(self._loot_items(entry))
            for item in free:
                self.mining.setdefault(item, set()).add(file_name)
            for item in silk - free:
                self.silk_mining.setdefault(item, set()).add(file_name)
            return
        items = set()
        for pool in loot.get("pools", []):
            for entry in pool.get("entries", []):
                items.update(self._loot_items(entry))
        for item in items:
            if category == "entities":
                self.drops.setdefault(item, set()).add(file_name)
            elif category in ("chests", "archaeology", "dispensers", "spawners"):
                for struct in self._structures_for(rel):
                    self.structures.setdefault(item, set()).add(struct)
            elif category == "shearing":
                continue  # wool/etc. from shearing a mob — covered by the mob, not a structure
            else:
                self.gameplay.setdefault(item, set()).add(file_name)

    def _trade(self, profession: str, file_name: str, trade: dict):
        gives = trade.get("gives", {})
        item = gives.get("id") or gives.get("item")
        if item:
            self.trades.setdefault(_strip_ns(item), set()).add((profession, file_name))

    def _food(self, mob: str, tag: dict):
        for value in tag.get("values", []):
            item = value if isinstance(value, str) else value.get("id", "")
            if item:
                self.breeding.setdefault(_strip_ns(item).replace("#", ""), set()).add(mob)

    # -- tags ---------------------------------------------------------------
    def _resolve_tag(self, tag_name: str, seen: set | None = None) -> list:
        """All concrete item ids in an item tag, following nested ``#tag`` references."""
        seen = seen if seen is not None else set()
        if tag_name in seen:
            return []
        seen.add(tag_name)
        items = []
        for value in self.item_tags.get(tag_name, []):
            entry = value if isinstance(value, str) else value.get("id", "")
            if not entry:
                continue
            if entry.startswith("#"):
                items.extend(self._resolve_tag(_strip_ns(entry[1:]), seen))
            else:
                items.append(_strip_ns(entry))
        return items

    def _expand_ingredient(self, ingredient: dict) -> dict:
        """Replace a ``{"tag": t}`` ingredient with ``{"any_of": [{"item": i}, ...]}`` members."""
        if "any_of" in ingredient:
            return {"any_of": [self._expand_ingredient(sub) for sub in ingredient["any_of"]]}
        if "tag" in ingredient:
            members = self._resolve_tag(ingredient["tag"])
            if members:
                return {"any_of": [{"item": item} for item in members]}
        return ingredient

    def _expanded_recipes(self, item: str) -> list:
        return [
            {"station": recipe["station"],
             "ingredients": [self._expand_ingredient(ing) for ing in recipe["ingredients"]]}
            for recipe in self.recipes[item]
        ]

    # -- emit ---------------------------------------------------------------
    def table(self) -> dict:
        items = set(self.recipes) | set(self.drops) | set(self.mining) | set(self.silk_mining) \
            | set(self.structures) | set(self.trades) | set(self.breeding) | set(self.gameplay)
        out: dict[str, dict] = {}
        for item in sorted(items):
            rec: dict = {}
            if item in self.recipes:
                rec["recipes"] = self._expanded_recipes(item)
            if item in self.drops:
                rec["drops"] = sorted(self.drops[item])
            if item in self.mining:
                rec["mining"] = sorted(self.mining[item])
            if item in self.silk_mining:
                rec["silk_mining"] = sorted(self.silk_mining[item])
            if item in self.structures:
                rec["structures"] = sorted(self.structures[item])
            if item in self.trades:
                rec["trades"] = sorted([list(t) for t in self.trades[item]])
            if item in self.breeding:
                rec["breeding"] = sorted(self.breeding[item])
            if item in self.gameplay:
                rec["gameplay"] = sorted(self.gameplay[item])
            out[item] = rec
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
        REPO, "minecraft", "packs", "vanilla_26_1", "acquisition.json")
    if not os.path.exists(source):
        print(f"source not found: {source}")
        print("usage: python tools/build_acquisition.py <jar|zip|dir> <out.json>")
        return 2
    builder = AcquisitionBuilder()
    builder.load(_entries(source))
    table = builder.table()
    with open(out, "w", encoding="utf-8") as f:
        json.dump(table, f, indent=2, ensure_ascii=False, sort_keys=True)
        f.write("\n")
    print(f"wrote acquisition for {len(table)} items to {out}")
    print("  recipes:", len(builder.recipes), "| drops:", len(builder.drops),
          "| mining:", len(builder.mining), "| structures:", len(builder.structures),
          "| trades:", len(builder.trades), "| breeding:", len(builder.breeding))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
