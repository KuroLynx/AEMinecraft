"""Build a content pack's enchanted-book table from a Minecraft jar: which loot tables can hand out a
book of each enchantment, and out of how many.

A loot table never names "enchanted_book": it hands out a ``book`` with an enchant function or
modifier (``enchant_randomly`` / ``enchant_with_levels`` / ``set_enchantments``) whose options say
what it can roll. One option is a sure thing (a bastion's book is always Soul Speed); several are luck
(a mineshaft book is any of ~40). Also writes the enchantment tags the logic needs, which tags.json
does not carry: what a librarian can sell and what the enchanting table offers.

Output::

    {"books": {"<enchantment>": {"<loot table path>": <options in that roll>, ...}, ...},
     "tradeable": [...], "in_enchanting_table": [...]}

Usage:

    python tools/build_enchanted_books.py                  # every loom-cached version -> its pack
    python tools/build_enchanted_books.py <jar> <out.json>
"""
import json
import os
import sys
import zipfile

REPO = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
VERSIONS = {"26.1.2": "minecraft_26_1_2", "26.2": "minecraft_26_2", "26.3": "minecraft_26_3"}
LOOT = "data/minecraft/loot_table/"
_ENCHANT_FUNCS = ("enchant_randomly", "enchant_with_levels", "set_enchantments")


def _bare(value: str) -> str:
    return value.split(":", 1)[-1]


class _Jar:
    def __init__(self, path: str):
        self.zip = zipfile.ZipFile(path)
        self.names = set(self.zip.namelist())

    def json(self, path: str):
        return json.loads(self.zip.read(path))

    def tag(self, name: str) -> set:
        """An enchantment tag, nested tags expanded, as bare ids."""
        out = set()
        for value in self.json(f"data/minecraft/tags/enchantment/{_bare(name)}.json")["values"]:
            value = value if isinstance(value, str) else value["id"]
            out |= self.tag(value[1:]) if value.startswith("#") else {_bare(value)}
        return out

    def all_enchantments(self) -> set:
        prefix = "data/minecraft/enchantment/"
        return {n[len(prefix):-len(".json")] for n in self.names if n.startswith(prefix) and n.endswith(".json")}

    def options(self, value) -> set:
        """An enchant function's options: a #tag, one id, a list of ids, or absent (anything)."""
        if value is None:
            return self.all_enchantments()
        if isinstance(value, str):
            return self.tag(value[1:]) if value.startswith("#") else {_bare(value)}
        return {_bare(v) for v in value}


def _functions(entry: dict) -> list:
    """The functions on a loot entry: ``functions`` (<= 26.2) or ``modifier`` (26.3), a sequence's
    ``modifiers`` flattened."""
    out = []
    for key in ("functions", "modifier", "modifiers"):
        value = entry.get(key)
        for func in value if isinstance(value, list) else [value] if isinstance(value, dict) else []:
            out.append(func)
            out += _functions(func)
    return out


def _walk(node, jar: _Jar, table: str, books: dict):
    if isinstance(node, dict):
        name = node.get("name")
        if isinstance(name, str) and _bare(name) == "book":
            for func in _functions(node):
                kind = _bare(str(func.get("function") or func.get("type") or ""))
                if kind not in _ENCHANT_FUNCS:
                    continue
                if kind == "set_enchantments":
                    rolled = {_bare(e) for e in func.get("enchantments") or {}}
                    count = 1   # every listed enchantment is applied
                else:
                    rolled = jar.options(func.get("options"))
                    count = len(rolled)
                for enchantment in rolled:
                    known = books.setdefault(enchantment, {}).get(table)
                    books[enchantment][table] = count if known is None else min(known, count)
        for value in node.values():
            _walk(value, jar, table, books)
    elif isinstance(node, list):
        for value in node:
            _walk(value, jar, table, books)


def build(jar_path: str) -> dict:
    jar = _Jar(jar_path)
    books: dict = {}
    for name in sorted(jar.names):
        if name.startswith(LOOT) and name.endswith(".json"):
            _walk(jar.json(name), jar, name[len(LOOT):-len(".json")], books)
    return {"books": {e: dict(sorted(t.items())) for e, t in sorted(books.items())},
            "tradeable": sorted(jar.tag("tradeable")),
            "in_enchanting_table": sorted(jar.tag("in_enchanting_table"))}


def _write(jar_path: str, out: str) -> None:
    data = build(jar_path)
    with open(out, "w", encoding="utf-8", newline="\n") as handle:
        json.dump(data, handle, indent=2)
        handle.write("\n")
    print(f"{out}: {len(data['books'])} enchantments")


def main():
    if len(sys.argv) == 3:
        _write(sys.argv[1], sys.argv[2])
        return
    for version, pack in VERSIONS.items():
        jar = os.path.join(os.path.expanduser("~"), ".gradle", "caches", "fabric-loom", version, "minecraft-client.jar")
        _write(jar, os.path.join(REPO, "minecraft_aem", "packs", pack, "enchanted_books.json"))


if __name__ == "__main__":
    main()
