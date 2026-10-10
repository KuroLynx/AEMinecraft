"""acquire() as a fixed point over the recipe graph: every (item, bulk) has ONE price.

It replaced a depth-first recursion that priced an item per (item, recursion stack), with a depth cap
against the blowup: the same item cost different things in different chains, and a deep chain died on
an item it never priced (a stick at depth 4). Here:

* every item starts unobtainable (None);
* computing an item runs the ordinary _acquire_compute, but each acquire() inside it returns the
  ingredient's CURRENT price and records the dependency — nothing recurses;
* when a price changes, only the items that read it are recomputed, until nothing changes.

A cycle just reads the other item's current price: iron ingot from iron block from iron ingot adds
an alternative the ingot already has, and the canonical form below absorbs it. That form is a minimal
DNF (an antichain of alternatives, each a set of leaves with a count per item), rebuilt as an interned
Rule, so an unchanged price is the same object and "did it change?" is an identity test.
"""
from __future__ import annotations

from collections import defaultdict, deque
from itertools import combinations

from . import ast
from .ast import And, AtLeast, Const, Has, Or, ReachLocation, ReachRegion, and_, or_

# ponytail: a hard stop, not a tuning knob. A price that keeps changing past this many recomputes is
# oscillating (_demote is not monotone), which is a bug to look at, not a budget to raise.
_MAX_RECOMPUTES = 200

_DNF: dict = {}   # Rule -> frozenset of alternatives (nodes are interned, so this is pure)


def _alt(atoms) -> frozenset:
    """One alternative: leaves ANDed, a Has count collapsed to its maximum per item."""
    best: dict = {}
    for kind, name, n in atoms:
        if n > best.get((kind, name), 0):
            best[(kind, name)] = n
    return frozenset((kind, name, n) for (kind, name), n in best.items())


_SIG: dict = {}   # alternative -> (its (kind, name) set, its leaves needing a count above 1); pure


def _sig(alt: frozenset) -> tuple:
    sig = _SIG.get(alt)
    if sig is None:
        sig = _SIG[alt] = (frozenset((kind, name) for kind, name, _ in alt),
                           tuple(((kind, name), n) for kind, name, n in alt if n > 1))
    return sig


def _minimal(alts) -> frozenset:
    """Drop every alternative that a kept one subsumes (each of its leaves present with at least its count).

    The name-set test is a C-level subset check; counts are compared only for the few leaves above 1."""
    keep: list = []
    kept: list = []
    # By size, then total count: whatever subsumes an alternative is then always looked at first. By
    # size alone, {Material 3, X} could come before {Material 2, X} and survive it, and which came
    # first was set order — the string hash — so the logic changed from one process to the next.
    for alt in sorted(set(alts), key=lambda alt: (len(alt), sum(n for _, _, n in alt))):
        names = _sig(alt)[0]
        have = None
        for k_names, k_counts in kept:
            if k_names <= names:
                if not k_counts:
                    break
                if have is None:
                    have = {(kind, name): n for kind, name, n in alt}
                if all(have[leaf] >= n for leaf, n in k_counts):
                    break
        else:
            keep.append(alt)
            kept.append(_sig(alt))
    return frozenset(keep)


def _join(a: frozenset, b: frozenset) -> frozenset:
    """``_alt(a | b)``: the union is already an alternative unless an item appears in both at two counts."""
    union = a | b
    if len(_sig(a)[0] | _sig(b)[0]) == len(union):
        return union
    return _alt(union)


def _and(*dnfs) -> frozenset:
    out = {frozenset()}
    for dnf in dnfs:
        out = _minimal(_join(a, b) for a in out for b in dnf)
        if not out:
            break
    return frozenset(out)


def dnf(rule) -> frozenset:
    """The rule as a minimal antichain of alternatives. Empty = unsatisfiable; {∅} = always true."""
    if rule in _DNF:
        return _DNF[rule]
    if isinstance(rule, Const):
        out = frozenset({frozenset()}) if rule.value else frozenset()
    elif isinstance(rule, Has):
        out = frozenset({frozenset({("h", rule.item, rule.count)})})
    elif isinstance(rule, ReachRegion):
        out = frozenset({frozenset({("r", rule.region, 1)})})
    elif isinstance(rule, ReachLocation):
        out = frozenset({frozenset({("l", rule.location, 1)})})
    elif isinstance(rule, And):
        out = _and(*(dnf(child) for child in rule.children))
    elif isinstance(rule, Or):
        out = _minimal(alt for child in rule.children for alt in dnf(child))
    elif isinstance(rule, AtLeast):
        out = _minimal(alt for combo in combinations(rule.children, rule.n)
                       for alt in _and(*(dnf(child) for child in combo)))
    else:
        raise TypeError(f"no DNF for {type(rule).__name__}")
    _DNF[rule] = out
    return out


def _leaf(player: int, atom):
    kind, name, n = atom
    if kind == "x":
        return ast._NODES[name]   # an opaque subtree (see _glitch_price), stored by its interned key
    if kind == "h":
        return Has(player, name, n)
    if kind == "r":
        return ReachRegion(player, name)
    return ReachLocation(player, name)


def canonical(player: int, rule):
    """The interned Rule for ``rule``'s minimal DNF, in a stable order."""
    alts = sorted(dnf(rule), key=lambda alt: (len(alt), sorted(alt)))
    return or_(*(and_(*(_leaf(player, atom) for atom in sorted(alt))) for alt in alts))


# A sub-rule whose minimal form is at most this many alternatives is taken from the shared cache when
# it is already there; a bigger one is expanded afresh, so its opaque parts stay opaque.
_REUSE_CAP = 64
# ponytail: fixed budgets, measured on a BACAP slot with every lock on. A glitch price whose minimal
# form would pass _GLITCH_DNF_CAP alternatives keeps its rule as built; one still changing after
# _GLITCH_ROUNDS recomputes stops there (see _solve).
_GLITCH_DNF_CAP = 256
_GLITCH_ROUNDS = 12


class _TooBig(Exception):
    pass


# Subtrees whose minimal form already came out too big once. Not retried: expanding them again only to
# give up again was most of a glitch build. Keeping a rule as built is exact either way, so this only
# decides how much gets minimised. ponytail: process-global like ast._NODES, one entry per node.
_TOO_BIG: set = set()


def _and_capped(*dnfs) -> frozenset:
    """``_and``, giving up before any step that could pass _GLITCH_DNF_CAP alternatives."""
    out = {frozenset()}
    for d in dnfs:
        if len(out) * len(d) > _GLITCH_DNF_CAP:
            raise _TooBig
        out = _minimal(_join(a, b) for a in out for b in d)
        if not out:
            break
    return frozenset(out)


def _dnf_opaque(rule, opaque: dict, memo: dict) -> frozenset:
    """``dnf`` with every node in ``opaque`` (by id) kept as one atom instead of being expanded.

    Exact: an atom stands for its own subtree, and putting the subtree back in its place gives a
    formula equivalent to ``rule``. Two alternatives that differ only inside an atom are just not
    merged, which costs size, never meaning."""
    if id(rule) in opaque:
        return frozenset({frozenset({("x", rule.key(), 1)})})
    out = memo.get(id(rule))
    if out is not None:
        return out
    if rule in _TOO_BIG:
        raise _TooBig
    try:
        out = _dnf_opaque_compute(rule, opaque, memo)
    except _TooBig:
        _TOO_BIG.add(rule)
        raise
    memo[id(rule)] = out
    return out


def _dnf_opaque_compute(rule, opaque: dict, memo: dict) -> frozenset:
    shared = _DNF.get(rule)
    if shared is not None and len(shared) <= _REUSE_CAP:
        out = shared
    elif isinstance(rule, (Const, Has, ReachRegion, ReachLocation)):
        out = dnf(rule)
    elif isinstance(rule, And):
        out = _and_capped(*(_dnf_opaque(child, opaque, memo) for child in rule.children))
    elif isinstance(rule, Or):
        out = _minimal(alt for child in rule.children for alt in _dnf_opaque(child, opaque, memo))
    elif isinstance(rule, AtLeast):
        out = _minimal(alt for combo in combinations(rule.children, rule.n)
                       for alt in _and_capped(*(_dnf_opaque(child, opaque, memo) for child in combo)))
    else:
        raise TypeError(f"no DNF for {type(rule).__name__}")
    if len(out) > _GLITCH_DNF_CAP:
        raise _TooBig
    return out


def _cycle(st, key) -> set:
    """The keys on a dependency cycle through ``key`` (its strongly connected component), or an empty
    set when ``key`` is on none. Edges only ever grow, so a cycle found late is caught on a later
    recompute; the answer is cached until a new edge appears."""
    cached = st.cycles.get(key)
    if cached is not None and cached[0] == st.edges:
        return cached[1]
    def reach(start, edges):
        seen, todo = set(), [start]
        while todo:
            for nxt in edges.get(todo.pop(), ()):
                if nxt not in seen:
                    seen.add(nxt)
                    todo.append(nxt)
        return seen
    ahead = reach(key, st.deps)
    cycle = ahead & reach(key, st.readers) if key in ahead else set()
    st.cycles[key] = (st.edges, cycle)
    return cycle


def _glitch_price(h, st, key, rule):
    """A glitch price, without the exponential part of the minimal form.

    The glitch graph keeps every unreliable route, so with structures and mobs locked an item has
    hundreds of alternatives, and canonical()'s DNF multiplies them across every ingredient: a BACAP
    slot sat in _minimal for good. The minimal form is only NEEDED to settle cycles — iron ingot from
    iron block from iron ingot keeps adding a route the ingot already has, which only the antichain
    absorbs. So:

    * an item on no cycle keeps its rule as built. Nodes are interned, so the same inputs give the
      very same node and "did it change?" stays an identity test;
    * an item on a cycle is put in minimal form, but every price from outside its cycle is one opaque
      atom. Those prices settle on their own, and only the cycle's own prices get expanded, which is
      what makes the cycle stop growing;
    * a cycle price whose minimal form would still be huge keeps its rule as built too. Glitch
      routes tie ~140 items into one cycle (trades, rewards, shared materials), so this happens. Such
      a price changes shape every round even when its meaning doesn't, so _solve stops updating it
      after _GLITCH_ROUNDS recomputes. Rounds only ever add routes, so a stopped price can only lack
      a route, never invent one: at worst a doable-by-luck check reads red instead of yellow.

    Fill never reads the glitch graph; strict prices still go through canonical() unchanged."""
    cycle = _cycle(st, key)
    if not cycle:
        return rule
    opaque = {}
    for dep in st.deps[key]:
        price = st.price.get(dep)
        if dep not in cycle and price is not None:
            opaque[id(price)] = price
    try:
        alts = sorted(_dnf_opaque(rule, opaque, {}), key=lambda alt: (len(alt), sorted(alt)))
    except _TooBig:
        return rule   # exact, just not minimal; _solve's round limit settles it if it keeps changing
    return or_(*(and_(*(_leaf(h.player, atom) for atom in sorted(alt))) for alt in alts))


class _State:
    def __init__(self):
        self.price: dict = {}                       # (base, bulk) -> Rule | None
        self.flags: dict = {}                       # (base, bulk) -> (loose, dry)
        # key -> keys whose computation read it. A dict used as an ordered set: a set's order follows the
        # string hash, which changes per process, and the order readers are re-queued in changes the result.
        self.readers: dict = defaultdict(dict)
        self.deps: dict = defaultdict(dict)         # the reverse: key -> keys its computation read
        self.edges = 0                              # dependency edges so far; versions _cycle's cache
        self.cycles: dict = {}                      # key -> (edges when computed, its cycle)
        self.recomputes: dict = defaultdict(int)
        self.work: deque = deque()
        self.queued: set = set()
        self.current = None                         # key being computed, while solving


def acquire(h, item_id: str):
    """RuleHelper.acquire: the item's price, solving whatever is not priced yet."""
    base = item_id.split(":", 1)[-1]
    if base.startswith("#"):
        return None   # a raw tag (recipe tags are pre-expanded; a bare tag can't be resolved)
    st = h.__dict__.get("_fp")
    if st is None:
        st = h._fp = _State()
    key = (base, h._bulk)
    if st.current is not None:
        # Inside a computation an unpriced item is "no route yet": Const(False) folds out of every
        # AND/OR the caller builds, where None would have to be checked at each of ~40 call sites.
        if key not in st.deps[st.current]:
            st.deps[st.current][key] = None
            st.readers[key][st.current] = None
            st.edges += 1
        if key not in st.price:
            st.price[key] = None
            _queue(st, key)
        price = st.price[key]
        if price is None:
            return Const(False)
        # The loose/dry marks go by node identity, and canonical prices are small shared nodes (a
        # flimsy item priced "Overworld" IS the Overworld node). So the marks live for one
        # computation and are set by the item being read, never left on the node for everyone.
        loose, dry = st.flags.get(key, (False, False))
        if loose:
            h._loose_nodes.add(id(price))
        if dry:
            h._dry_nodes.add(id(price))
        return price
    if key not in st.price:
        st.price[key] = None
        _queue(st, key)
        _solve(h, st)
    return st.price[key]


def _queue(st: _State, key) -> None:
    if key not in st.queued:
        st.queued.add(key)
        st.work.append(key)


def _solve(h, st: _State) -> None:
    saved = h._bulk
    try:
        while st.work:
            key = st.work.popleft()
            st.queued.discard(key)
            st.recomputes[key] += 1
            if h.glitch and st.recomputes[key] > _GLITCH_ROUNDS:
                continue   # see _glitch_price: this price stays where it is
            if st.recomputes[key] > _MAX_RECOMPUTES:
                raise RuntimeError(f"fixed-point acquire: {key} still changing after {_MAX_RECOMPUTES} recomputes")
            base, bulk = key
            h._bulk = bulk
            h._sourced_loose = h._sourced_dry = False
            h._loose_nodes, h._dry_nodes = set(), set()
            st.current = key
            try:
                rule = h._acquire_compute(base)
            finally:
                st.current = None
            if rule is not None:
                # Having an item means holding it somewhere: with every inventory slot locked
                # (inventory_lock 'slots' at 36) nothing can be picked up until the first one opens.
                # Part of the price itself, so the loose/dry marks (by node identity) still find it.
                rule = h.all_of(h.slot_group("carry"), rule)
            if rule is None:
                new = None
            elif h.glitch:
                new = _glitch_price(h, st, key, rule)
            else:
                new = canonical(h.player, rule)
            if isinstance(new, Const) and not new.value:
                new = None
            flags = (h._sourced_loose, h._sourced_dry)
            old = st.price[key]
            if new is old and flags == st.flags.get(key, (False, False)):
                continue
            st.price[key], st.flags[key] = new, flags
            for reader in st.readers[key]:
                _queue(st, reader)
    finally:
        h._bulk = saved
