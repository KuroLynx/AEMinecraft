"""Filler (buffs + safe item stacks) & trap items, all defined in ``content/filler.csv``.

Everything the mod hands out on receipt is declared in one editable table — ``content/filler.csv`` —
so tuning weights, quantities or adding a new filler needs no code change. Three ``kind``s of row:

* ``buff`` — grants a temporary **buff** the mod reproduces *mechanically* (attribute modifiers + tick
  logic — see the mod's ``FillerBuffService``), never a ``minecraft:`` ``MobEffect``, so it can't fire
  ``effects_changed`` or complete any potion/effect advancement. Columns: ``name``, ``key`` (buff id),
  ``weights`` (a single filler-pool weight).
* ``item`` — hands the player a real Minecraft **stack**. Safe only because every listed item is
  *verified advancement-safe*: it appears in no advancement except BACAP's "get/stack each item|block"
  challenges (a free stack can't complete one — those need ~every item) and is not a crafting
  ingredient / brewing reagent / trim material / access item (so it can't open a location out of
  logic). Columns: ``item`` (mc id), ``quantities`` + ``weights`` (parallel space-separated tiers — a
  bigger stack gets a smaller weight, so it's RARER). One AP item is generated per tier, named
  ``Filler: <Prettified Item> x<qty>``.
* ``trap`` — fires a one-shot ``TrapEffects`` effect. Columns like ``buff`` (``key`` = effect id).

The CSV **row order fixes each AP item's id** (assigned sequentially from ``BASE_ID_FILLER_ITEM``), so
reorder nothing once seeds exist — only append. The mod applies each received copy exactly once (it
tracks a persisted high-water mark, since Archipelago replays the whole item history on reconnect).
"""
from __future__ import annotations

import csv
from importlib.resources import files

from BaseClasses import ItemClassification

from .content.registry import MCItemData

# Base duration (seconds) granted per received buff copy. Duration STACKS: each copy adds this to the
# buff's remaining timer (the mod does the accumulation). Kept in slot_data so the apworld stays the
# source of truth for tuning.
BUFF_SECONDS_PER_COPY = 30

# Dedicated AP item-id block for the generated filler/trap items, kept clear of the CSV item ids
# (BASE_ID_ITEMS, only 256 wide before the unlock bases) and every location base, so the table can
# hold as many fillers as we like. See content/registry.py for the other id bases; the next used base
# is BASE_ID_LOC_BACAP (0xEC2000, < 0x1000 locations), so 0xEC3000 has a clear gap.
BASE_ID_FILLER_ITEM = 0xEC3000


def _prettify(mc_item: str) -> str:
    """``mossy_cobblestone_slab`` -> ``Mossy Cobblestone Slab`` for the AP item label."""
    return " ".join(word[:1].upper() + word[1:] for word in mc_item.split("_") if word)


def _read_filler_csv() -> list[dict]:
    """Rows of ``content/filler.csv`` (utf-8-sig tolerates a BOM, like items.csv)."""
    path = files(__package__).joinpath("content").joinpath("filler.csv")
    with path.open(mode="r", encoding="utf-8-sig") as handle:
        return list(csv.DictReader(handle))


# Parse the table once at import into the lookups the world and the slot-data export consume:
BUFF_FILLER: dict[str, str] = {}          # AP name -> buff key            (FillerBuffService)
TRAP_EFFECTS: dict[str, str] = {}         # AP name -> trap effect key     (TrapEffects)
ITEM_FILLER_GRANTS: dict[str, tuple[str, int]] = {}  # AP name -> (mc item id, quantity granted)
# AP name -> MCItemData for EVERY generated filler/trap item (``count`` doubles as the filler-pool
# weight). data.py merges this into ITEMS so the world registers, creates and weights each one.
FILLER_ITEMS: dict[str, MCItemData] = {}

_next_filler_id = BASE_ID_FILLER_ITEM
for _row in _read_filler_csv():
    _kind = (_row.get("kind") or "").strip()
    _weights = (_row.get("weights") or "").split()
    if _kind == "buff":
        _name = _row["name"].strip()
        BUFF_FILLER[_name] = _row["key"].strip()
        FILLER_ITEMS[_name] = MCItemData(_next_filler_id, ItemClassification.filler, int(_weights[0]))
        _next_filler_id += 1
    elif _kind == "trap":
        _name = _row["name"].strip()
        TRAP_EFFECTS[_name] = _row["key"].strip()
        FILLER_ITEMS[_name] = MCItemData(_next_filler_id, ItemClassification.trap, int(_weights[0]))
        _next_filler_id += 1
    elif _kind == "item":
        _mc_item = _row["item"].strip()
        for _quantity, _weight in zip((_row.get("quantities") or "").split(), _weights):
            _name = f"Filler: {_prettify(_mc_item)} x{_quantity}"
            ITEM_FILLER_GRANTS[_name] = (_mc_item, int(_quantity))
            FILLER_ITEMS[_name] = MCItemData(_next_filler_id, ItemClassification.filler, int(_weight))
            _next_filler_id += 1


def build_filler_export(item_name_to_id: dict[str, int]) -> dict[str, dict]:
    """``{item_id: grant}`` for every filler item — a buff grant ``{"buff": key, "seconds": n}`` or
    an item grant ``{"item": "minecraft:<id>", "count": n}``. The mod dispatches on which key is set."""
    export: dict[str, dict] = {}
    for name, key in BUFF_FILLER.items():
        item_id = item_name_to_id.get(name)
        if item_id is not None:
            export[str(item_id)] = {"buff": key, "seconds": BUFF_SECONDS_PER_COPY}
    for name, (mc_item, quantity) in ITEM_FILLER_GRANTS.items():
        item_id = item_name_to_id.get(name)
        if item_id is not None:
            export[str(item_id)] = {"item": f"minecraft:{mc_item}", "count": quantity}
    return export


def build_trap_export(item_name_to_id: dict[str, int]) -> dict[str, str]:
    """``{item_id: effect_key}`` for each trap item."""
    export: dict[str, str] = {}
    for name, key in TRAP_EFFECTS.items():
        item_id = item_name_to_id.get(name)
        if item_id is not None:
            export[str(item_id)] = key
    return export
