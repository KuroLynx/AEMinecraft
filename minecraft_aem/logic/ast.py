"""Serializable logic rule AST.

Each rule the apworld sets is built from these nodes instead of a bare ``lambda``.
A node is **both**:

* callable — ``node(state) -> bool`` — so it can be handed straight to AP's
  ``set_rule`` and evaluated against a ``CollectionState`` during generation, and
* serializable — ``node.to_dict()`` — so the exact same logic can be shipped to the
  Fabric mod (see ``minecraft/logic_export.py``) and re-evaluated in Java.

This makes the Python rules the single source of truth: when they change, the export
regenerates, with no parallel logic to maintain.

All option-dependent branching is resolved at build time (in ``helpers.py``), so the
only node kinds that ever reach serialization are the primitives below.

Serialized form (compact, mirrored by the Java parser):
    const  : {"k": "const",  "v": <bool>}
    has    : {"k": "has",    "i": <item name>, "n": <count>}
    region : {"k": "region", "r": <region name>}
    loc    : {"k": "loc",    "l": <AP location name>}
    and    : {"k": "and",    "c": [<node>, ...]}
    or     : {"k": "or",     "c": [<node>, ...]}
    atleast: {"k": "atleast", "n": <count>, "c": [<node>, ...]}
"""
from __future__ import annotations

import json
from typing import Iterable


# Rule.key() interning table: structural shape -> small int.
# ponytail: process-global and never cleared, one entry per distinct subtree; fine for a Generate run,
# scope it per world if a long-lived process ever generates many seeds.
_KEY_IDS: dict = {}


class Rule:
    """Base class: a node is callable against an AP state and serializable to a dict.

    Nodes are immutable once built and shared read-only across rules (the acquire() cache hands the
    same subtree to many rules). ``to_dict`` / ``canonical_json`` are therefore memoized: datapack-
    scale compilation serializes the same shared subtrees millions of times, and caching collapses
    that to once per node. Every consumer treats the serialized form as read-only (the mod export
    copies before rewriting), so returning a shared cached dict is safe."""

    _dict_cache = None
    _json_cache = None
    _key_cache = None
    _gate_cache = None
    _size_cache = None

    def __call__(self, state) -> bool:  # pragma: no cover - overridden
        raise NotImplementedError

    def to_dict(self) -> dict:
        cached = self._dict_cache
        if cached is None:
            cached = self._dict_cache = self._to_dict()
        return cached

    def _to_dict(self) -> dict:  # pragma: no cover - overridden
        raise NotImplementedError

    def canonical_json(self) -> str:
        """Stable ``sort_keys`` JSON of this subtree, memoized. Composites build it by joining their
        children's cached strings (see ``_canonical_json``) instead of re-encoding the whole object
        graph, so a shared subtree is serialized once and reused everywhere it occurs — the byte-exact
        equivalent of ``json.dumps(self.to_dict(), sort_keys=True)`` used for the _coarsen size cap."""
        cached = self._json_cache
        if cached is None:
            cached = self._json_cache = self._canonical_json()
        return cached

    def _canonical_json(self) -> str:
        # Leaves have no children to reuse — encode directly (also handles string escaping).
        return json.dumps(self._to_dict(), sort_keys=True)

    def serialized_size(self) -> int:
        """Length of ``canonical_json()`` WITHOUT building it — memoized, bottom-up.

        The size cap in ``_coarsen`` only ever asked how long the serialization would be, but
        ``canonical_json`` answers that by caching, on every node, a string holding its entire
        subtree. In the strict graph _demote keeps subtrees small; in the glitch graph with
        structure and mob unlocks on, nothing is pruned, so those strings go quadratic — a single
        no-BACAP world reached 22 GB and was OOM-killed at output. A length is additive, so a
        composite gets it from its children's cached lengths and allocates nothing.
        """
        cached = self._size_cache
        if cached is None:
            cached = self._size_cache = self._serialized_size()
        return cached

    def _serialized_size(self) -> int:
        # Leaves: the string is small, and building it is the honest way to count its escaping.
        return len(self._canonical_json())

    def key(self):
        """Structural key, memoized: an int, equal for two subtrees iff their canonical_json is
        equal (same kind/fields and same child keys, order-sensitive) — the cheap dedup key used by
        _unique_or and and_ in place of serializing every operand.

        An int, not the nested tuple ``_key`` describes: CPython never caches a tuple's hash, so
        every set lookup re-hashed the whole subtree as if it were a tree, not the shared DAG it is.
        A BACAP glitch export sat at "Beginning output..." for 15+ minutes doing exactly that.
        Interning makes each ``_key`` a flat tuple of the children's ints."""
        cached = self._key_cache
        if cached is None:
            shape = self._key()
            cached = self._key_cache = _KEY_IDS.setdefault(shape, len(_KEY_IDS))
        return cached

    def _key(self):  # pragma: no cover - overridden
        raise NotImplementedError

    def gate_summary(self) -> tuple:
        """``(gated, regions)`` for this subtree, memoized bottom-up: ``gated`` is True if any leaf is
        a real gate (``has``/``loc``), ``regions`` is the set of every ``region`` leaf. Lets _coarsen
        decide "region-only, collapse to its region floor" without walking the serialized dict."""
        cached = self._gate_cache
        if cached is None:
            cached = self._gate_cache = self._gate_summary()
        return cached

    def _gate_summary(self) -> tuple:  # pragma: no cover - overridden
        raise NotImplementedError

    @staticmethod
    def _merge_gate(children) -> tuple:
        """Combine children summaries: gated if any child gates; regions is the union across all."""
        gated = False
        regions: set = set()
        for child in children:
            c_gated, c_regions = child.gate_summary()
            gated = gated or c_gated
            regions |= c_regions
        return gated, frozenset(regions)


# Literal pieces of a composite's canonical JSON, so serialized_size counts exactly what
# _canonical_json would have written.
_JSON_OPEN = '{"c": ['
_JSON_SEP = ", "


def _composite_size(children, close: str) -> int:
    """Serialized length of a composite: the open bracket, the children joined by ", ", the close."""
    total = len(_JSON_OPEN) + len(close)
    if children:
        total += sum(child.serialized_size() for child in children)
        total += len(_JSON_SEP) * (len(children) - 1)
    return total


class Const(Rule):
    def __init__(self, value: bool):
        self.value = bool(value)

    def __call__(self, state) -> bool:
        return self.value

    def _to_dict(self) -> dict:
        return {"k": "const", "v": self.value}

    def _key(self):
        return ("const", self.value)

    def _gate_summary(self) -> tuple:
        return (False, frozenset())


class Has(Rule):
    def __init__(self, player: int, item: str, count: int = 1):
        self.player = player
        self.item = item
        self.count = count

    def __call__(self, state) -> bool:
        return state.has(self.item, self.player, self.count)

    def _to_dict(self) -> dict:
        return {"k": "has", "i": self.item, "n": self.count}

    def _key(self):
        return ("has", self.item, self.count)

    def _gate_summary(self) -> tuple:
        return (True, frozenset())


class ReachRegion(Rule):
    def __init__(self, player: int, region: str):
        self.player = player
        # Normalise to a plain str: region may be a (str, Enum) member (MCRegion),
        # whose str()/repr leaks "MCRegion.NETHER"; concatenation yields the value.
        self.region = region + "" if isinstance(region, str) else str(region)

    def __call__(self, state) -> bool:
        return state.can_reach_region(self.region, self.player)

    def _to_dict(self) -> dict:
        return {"k": "region", "r": self.region}

    def _key(self):
        return ("region", self.region)

    def _gate_summary(self) -> tuple:
        return (False, frozenset((self.region,)))


class ReachLocation(Rule):
    def __init__(self, player: int, location: str):
        self.player = player
        self.location = location

    def __call__(self, state) -> bool:
        return state.can_reach_location(self.location, self.player)

    def _to_dict(self) -> dict:
        return {"k": "loc", "l": self.location}

    def _key(self):
        return ("loc", self.location)

    def _gate_summary(self) -> tuple:
        return (True, frozenset())


class AtLeast(Rule):
    """True iff at least ``n`` of ``children`` are true. Compact alternative to an OR over
    every n-combination (which is C(len, n) terms and explodes the serialized tree)."""

    def __init__(self, n: int, children: list[Rule]):
        self.n = n
        self.children = children

    def __call__(self, state) -> bool:
        satisfied = 0
        for child in self.children:
            if child(state):
                satisfied += 1
                if satisfied >= self.n:
                    return True
        return False

    def _to_dict(self) -> dict:
        return {"k": "atleast", "n": self.n, "c": [child.to_dict() for child in self.children]}

    def _canonical_json(self) -> str:
        return ('{"c": [' + ", ".join(c.canonical_json() for c in self.children)
                + '], "k": "atleast", "n": ' + str(self.n) + "}")

    def _serialized_size(self) -> int:
        return _composite_size(self.children, '], "k": "atleast", "n": ' + str(self.n) + "}")

    def _key(self):
        return ("atleast", self.n, tuple(c.key() for c in self.children))

    def _gate_summary(self) -> tuple:
        return self._merge_gate(self.children)


class And(Rule):
    def __init__(self, children: list[Rule]):
        self.children = children

    def __call__(self, state) -> bool:
        return all(child(state) for child in self.children)

    def _to_dict(self) -> dict:
        return {"k": "and", "c": [child.to_dict() for child in self.children]}

    def _canonical_json(self) -> str:
        return '{"c": [' + ", ".join(c.canonical_json() for c in self.children) + '], "k": "and"}'

    def _serialized_size(self) -> int:
        return _composite_size(self.children, '], "k": "and"}')

    def _key(self):
        return ("and", tuple(c.key() for c in self.children))

    def _gate_summary(self) -> tuple:
        return self._merge_gate(self.children)


class Or(Rule):
    def __init__(self, children: list[Rule]):
        self.children = children

    def __call__(self, state) -> bool:
        return any(child(state) for child in self.children)

    def _to_dict(self) -> dict:
        return {"k": "or", "c": [child.to_dict() for child in self.children]}

    def _canonical_json(self) -> str:
        return '{"c": [' + ", ".join(c.canonical_json() for c in self.children) + '], "k": "or"}'

    def _serialized_size(self) -> int:
        return _composite_size(self.children, '], "k": "or"}')

    def _key(self):
        return ("or", tuple(c.key() for c in self.children))

    def _gate_summary(self) -> tuple:
        return self._merge_gate(self.children)


# ---------------------------------------------------------------------------
# Factories with flattening / simplification
#
# Keep the tree small and readable: flatten nested And/And and Or/Or, drop the
# identity element, and short-circuit the annihilator. ``all_of()`` with no
# arguments is True (matches Python's ``all([])``); ``any_of()`` is False.
# ---------------------------------------------------------------------------

def const(value: bool) -> Rule:
    return Const(value)


def and_(*rules: Rule) -> Rule:
    children: list[Rule] = []
    for rule in rules:
        if isinstance(rule, Const):
            if not rule.value:
                return Const(False)  # annihilator: anything AND False == False
            continue  # identity: drop Const(True)
        if isinstance(rule, And):
            children.extend(rule.children)  # flatten
        else:
            children.append(rule)
    # Idempotence: X AND X == X. Free correctness-wise, and it means a requirement can be AND-ed on
    # from more than one place without bloating the tree — the player predicate is now applied
    # centrally in TriggerCompiler._criterion while several handlers still fold it in themselves.
    # Dedup AFTER flattening so a repeat nested inside an And is caught too. Order is preserved
    # (first occurrence wins) to keep serialized output stable across runs.
    if len(children) > 1:
        seen = set()
        deduped = []
        for child in children:
            k = child.key()
            if k not in seen:
                seen.add(k)
                deduped.append(child)
        children = deduped
    if not children:
        return Const(True)
    if len(children) == 1:
        return children[0]
    return And(children)


def or_(*rules: Rule) -> Rule:
    children: list[Rule] = []
    for rule in rules:
        if isinstance(rule, Const):
            if rule.value:
                return Const(True)  # annihilator: anything OR True == True
            continue  # identity: drop Const(False)
        if isinstance(rule, Or):
            children.extend(rule.children)  # flatten
        else:
            children.append(rule)
    if not children:
        return Const(False)
    if len(children) == 1:
        return children[0]
    return Or(children)


def at_least(n: int, rules: Iterable[Rule]) -> Rule:
    """At least ``n`` of ``rules`` hold. Resolves Const children at build time, then degrades to
    the simplest equivalent node: Const, Or (n==1) or And (n==count) where possible, else AtLeast.
    Keeps the serialized tree O(len(rules)) instead of O(C(len, n))."""
    children: list[Rule] = []
    always_true = 0
    for rule in rules:
        if isinstance(rule, Const):
            if rule.value:
                always_true += 1
            continue  # Const(False) can never count toward the threshold — drop it.
        children.append(rule)
    need = n - always_true
    if need <= 0:
        return Const(True)
    if need > len(children):
        return Const(False)  # not enough candidates left to reach the threshold
    if need == 1:
        return or_(*children)
    if need == len(children):
        return and_(*children)
    return AtLeast(need, children)


def and_of(rules: Iterable[Rule]) -> Rule:
    return and_(*rules)


def or_of(rules: Iterable[Rule]) -> Rule:
    return or_(*rules)
