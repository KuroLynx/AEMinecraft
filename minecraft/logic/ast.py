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

from typing import Iterable


class Rule:
    """Base class: a node is callable against an AP state and serializable to a dict."""

    def __call__(self, state) -> bool:  # pragma: no cover - overridden
        raise NotImplementedError

    def to_dict(self) -> dict:  # pragma: no cover - overridden
        raise NotImplementedError


class Const(Rule):
    def __init__(self, value: bool):
        self.value = bool(value)

    def __call__(self, state) -> bool:
        return self.value

    def to_dict(self) -> dict:
        return {"k": "const", "v": self.value}


class Has(Rule):
    def __init__(self, player: int, item: str, count: int = 1):
        self.player = player
        self.item = item
        self.count = count

    def __call__(self, state) -> bool:
        return state.has(self.item, self.player, self.count)

    def to_dict(self) -> dict:
        return {"k": "has", "i": self.item, "n": self.count}


class ReachRegion(Rule):
    def __init__(self, player: int, region: str):
        self.player = player
        # Normalise to a plain str: region may be a (str, Enum) member (MCRegion),
        # whose str()/repr leaks "MCRegion.NETHER"; concatenation yields the value.
        self.region = region + "" if isinstance(region, str) else str(region)

    def __call__(self, state) -> bool:
        return state.can_reach_region(self.region, self.player)

    def to_dict(self) -> dict:
        return {"k": "region", "r": self.region}


class ReachLocation(Rule):
    def __init__(self, player: int, location: str):
        self.player = player
        self.location = location

    def __call__(self, state) -> bool:
        return state.can_reach_location(self.location, self.player)

    def to_dict(self) -> dict:
        return {"k": "loc", "l": self.location}


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

    def to_dict(self) -> dict:
        return {"k": "atleast", "n": self.n, "c": [child.to_dict() for child in self.children]}


class And(Rule):
    def __init__(self, children: list[Rule]):
        self.children = children

    def __call__(self, state) -> bool:
        return all(child(state) for child in self.children)

    def to_dict(self) -> dict:
        return {"k": "and", "c": [child.to_dict() for child in self.children]}


class Or(Rule):
    def __init__(self, children: list[Rule]):
        self.children = children

    def __call__(self, state) -> bool:
        return any(child(state) for child in self.children)

    def to_dict(self) -> dict:
        return {"k": "or", "c": [child.to_dict() for child in self.children]}


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
