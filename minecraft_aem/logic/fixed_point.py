"""acquire() as a fixed point over the recipe graph: every (item, bulk) has ONE price.

It replaced a depth-first recursion that priced an item per (item, recursion stack), with a depth cap
against the blowup: the same item cost different things in different chains, and a deep chain died on
an item it never priced (a stick at depth 4). Here:

* every item starts unobtainable (None);
* computing an item runs the ordinary _acquire_compute, but each acquire() inside it returns the
  ingredient's CURRENT price and records the dependency — nothing recurses;
* when a price changes, only the items that read it are recomputed, until nothing changes.

A cycle just reads the other item's current price: iron ingot from iron block from iron ingot adds
an alternative the ingot already has, and the canonical form below absorbs it. That form is the price's
minimal alternatives held as a ZDD and rebuilt as an interned Rule, so an unchanged price is the same
object and "did it change?" is an identity test.

The glitch graph keeps every unreliable route, so that minimal form is far too big there (hundreds of
alternatives per item with structures and mobs locked). It is never built: a glitch price keeps its
rule as built, and a read of an item on the reader's own cycle is a PriceRef to the item's price, not a
copy of it. Copying is what made a cycle grow: each round's price held the previous round's, 760
levels deep on a BACAP slot. With refs, a cycle's prices stop changing once the prices feeding it from
outside do, and the export's evaluators solve the refs as a least fixed point. Fill never reads the
glitch graph.
"""
from __future__ import annotations

from collections import defaultdict, deque

from .ast import And, AtLeast, Const, Has, Or, PriceRef, ReachLocation, ReachRegion, and_, or_

# ponytail: a hard stop, not a tuning knob. A price that keeps changing past this many recomputes is
# oscillating (_demote is not monotone), which is a bug to look at, not a budget to raise.
_MAX_RECOMPUTES = 200

# --- the strict canonical form: a zero-suppressed decision diagram (ZDD) ----------------------------
#
# A price's minimal DNF as a ZDD: a node is (var, lo, hi), the alternatives without ``var`` and those
# with it. Equal sub-families are one node, so the alternatives every mined item shares (its pickaxe's
# routes) are stored and combined once. Spelling the DNF out did that per alternative and cost ~2 min
# per BACAP slot once leather and string became dependable (c6bbea2); the idea and the speedup are
# MissingNoShiny's. Node 0 is the empty family (false), node 1 is {∅} (true).
#
# A count is a ladder: has(x, 3) is the set {x>=3, x>=2, x>=1}, so the plain set operations get counts
# right (a union of ladders is the higher count; a lower count is a subset). The rungs of one leaf are
# adjacent in the variable order, highest first.

_RUNGS = 64                     # ponytail: counts above 63 need a wider ladder; none come near it
_TERMINAL = 1 << 62             # the terminals' var: below every real one
_VAR, _LO, _HI = [_TERMINAL, _TERMINAL], [0, 1], [0, 1]
_UNIQUE: dict = {}              # (var, lo, hi) -> node
_LEAF_INDEX: dict = {}          # (kind, name) -> index, in first-seen order (deterministic)
_ATOM: dict = {}                # var -> (kind, name, count)
_UNION: dict = {}
_JOIN: dict = {}
_NONSUP: dict = {}
_MINIMAL: dict = {}
_OF_RULE: dict = {}             # Rule -> node
_AS_RULE: dict = {}             # node -> Rule


def _reset() -> None:
    """Forget every node: leaves carry the player, so nothing from an earlier slot can hit again."""
    del _VAR[2:], _LO[2:], _HI[2:]
    for table in (_UNIQUE, _LEAF_INDEX, _ATOM, _UNION, _JOIN, _NONSUP, _MINIMAL, _OF_RULE, _AS_RULE):
        table.clear()


def _mk(var: int, lo: int, hi: int) -> int:
    if hi == 0:
        return lo   # zero suppression: no alternative holds var
    key = (var, lo, hi)
    node = _UNIQUE.get(key)
    if node is None:
        node = _UNIQUE[key] = len(_VAR)
        _VAR.append(var)
        _LO.append(lo)
        _HI.append(hi)
    return node


def _union(f: int, g: int) -> int:
    if f == 0 or f == g:
        return g
    if g == 0:
        return f
    if f > g:
        f, g = g, f
    key = (f, g)
    out = _UNION.get(key)
    if out is None:
        vf, vg = _VAR[f], _VAR[g]
        if vf < vg:
            out = _mk(vf, _union(_LO[f], g), _HI[f])
        elif vf > vg:
            out = _mk(vg, _union(f, _LO[g]), _HI[g])
        else:
            out = _mk(vf, _union(_LO[f], _LO[g]), _union(_HI[f], _HI[g]))
        _UNION[key] = out
    return out


def _join(f: int, g: int) -> int:
    """Every ``a | b``: the AND of two families."""
    if f == 0 or g == 0:
        return 0
    if f == 1:
        return g
    if g == 1:
        return f
    if f > g:
        f, g = g, f
    key = (f, g)
    out = _JOIN.get(key)
    if out is None:
        vf, vg = _VAR[f], _VAR[g]
        if vf < vg:
            out = _mk(vf, _join(_LO[f], g), _join(_HI[f], g))
        elif vf > vg:
            out = _mk(vg, _join(f, _LO[g]), _join(f, _HI[g]))
        else:
            lf, hf, lg, hg = _LO[f], _HI[f], _LO[g], _HI[g]
            out = _mk(vf, _join(lf, lg), _union(_union(_join(hf, hg), _join(hf, lg)), _join(lf, hg)))
        _JOIN[key] = out
    return out


def _has_empty(f: int) -> bool:
    while f > 1:
        f = _LO[f]
    return f == 1


def _nonsup(f: int, g: int) -> int:
    """The alternatives of ``f`` holding no alternative of ``g`` (nothing ``g`` subsumes)."""
    if f == 0 or g == 1 or f == g:
        return 0
    if g == 0:
        return f
    if f == 1:
        return 0 if _has_empty(g) else 1
    key = (f, g)
    out = _NONSUP.get(key)
    if out is None:
        vf, vg = _VAR[f], _VAR[g]
        if vf < vg:
            out = _mk(vf, _nonsup(_LO[f], g), _nonsup(_HI[f], g))
        elif vf > vg:
            out = _nonsup(f, _LO[g])   # g's alternatives with vg can't fit in f's, which never hold it
        else:
            out = _mk(vf, _nonsup(_LO[f], _LO[g]), _nonsup(_nonsup(_HI[f], _LO[g]), _HI[g]))
        _NONSUP[key] = out
    return out


def _minimal(f: int) -> int:
    """Drop every alternative another one subsumes."""
    if f <= 1:
        return f
    out = _MINIMAL.get(f)
    if out is None:
        lo = _minimal(_LO[f])
        out = _MINIMAL[f] = _mk(_VAR[f], lo, _nonsup(_minimal(_HI[f]), lo))
    return out


def _leaf_zdd(kind: str, name: str, count: int) -> int:
    if count >= _RUNGS:
        raise ValueError(f"has({name}, {count}): past the {_RUNGS}-rung ladder")
    base = _LEAF_INDEX.setdefault((kind, name), len(_LEAF_INDEX)) * _RUNGS + _RUNGS
    node = 1
    for k in range(1, count + 1):   # bottom up: the lowest rung has the largest var
        var = base - k
        _ATOM[var] = (kind, name, k)
        node = _mk(var, 0, node)
    return node


def _zdd(rule) -> int:
    """The rule as a ZDD of its minimal alternatives."""
    out = _OF_RULE.get(rule)
    if out is not None:
        return out
    kind = type(rule)
    if kind is Const:
        out = 1 if rule.value else 0
    elif kind is Has:
        out = _leaf_zdd("h", rule.item, rule.count)
    elif kind is ReachRegion:
        out = _leaf_zdd("r", rule.region, 1)
    elif kind is ReachLocation:
        out = _leaf_zdd("l", rule.location, 1)
    elif kind is And:
        out = 1
        for child in rule.children:
            out = _minimal(_join(out, _zdd(child)))
            if out == 0:
                break
    elif kind is Or:
        out = 0
        for child in rule.children:
            out = _union(out, _zdd(child))
        out = _minimal(out)
    elif kind is AtLeast:
        kids = [_zdd(child) for child in rule.children]
        # row[i] = "at least n of kids[i:]", one n at a time: n of kids[i:] = kids[i] AND n-1 of the
        # rest, OR n of the rest.
        row = [1] * (len(kids) + 1)
        for n in range(1, rule.n + 1):
            new = [0] * (len(kids) + 1)
            for i in range(len(kids) - 1, -1, -1):
                new[i] = _minimal(_union(_join(kids[i], row[i + 1]), new[i + 1]))
            row = new
        out = row[0]
    else:
        raise TypeError(f"no ZDD for {kind.__name__}")
    _OF_RULE[rule] = out
    return out


def _as_rule(player: int, node: int):
    """The interned Rule for a node: OR over its var (without it | with it), so shared nodes are shared
    Rule subtrees."""
    if node <= 1:
        return Const(node == 1)
    out = _AS_RULE.get(node)
    if out is None:
        var = _VAR[node]
        kind, name, count = _ATOM[var]
        hi = _HI[node]
        while hi > 1 and _VAR[hi] // _RUNGS == var // _RUNGS:
            hi = _HI[hi]   # the lower rungs under a higher one: every alternative here holds them
        leaf = Has(player, name, count) if kind == "h" else (
            ReachRegion(player, name) if kind == "r" else ReachLocation(player, name))
        out = _AS_RULE[node] = or_(_as_rule(player, _LO[node]), and_(leaf, _as_rule(player, hi)))
        _OF_RULE.setdefault(out, node)   # re-reading this price later costs nothing
    return out


def canonical(player: int, rule):
    """The interned Rule for ``rule``'s minimal alternatives: the same object for the same logic."""
    return _as_rule(player, _zdd(rule))


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
        # Leaves carry the player, so nothing cached for an earlier slot can hit again: keeping it
        # stacked every AEM slot of a multiworld on top of the next.
        _reset()
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
        if h.glitch and key in _cycle(st, st.current):
            price = PriceRef(h.player, price_name(key))   # see the module docstring
        # The loose/dry marks go by node identity, and canonical prices are small shared nodes (a
        # flimsy item priced "Overworld" IS the Overworld node). So the marks live for one
        # computation and are set by the item being read, never left on the node for everyone.
        loose, dry = st.flags.get(key, (False, False))
        if (loose or dry) and not h.glitch:
            # A strict price is factored, so a route that ANDs on something the price already holds
            # (a crop's farm, around its seed) folds back into the price's own node, and the mark
            # meant for the read lands on the route. A one-child Or is a node no and_/or_ builds;
            # canonical() takes it out again, so it never reaches a rule. (Glitch prices aren't
            # factored: left as they were.)
            price = Or([price])
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


def price_name(key) -> str:
    """A price's name in the exported price table: the item, marked when it prices a whole stack."""
    base, bulk = key
    return f"{base} (stack)" if bulk else base


def price_table(h) -> dict:
    """Every glitch price by price_name, for the export to resolve PriceRefs against. None (no route)
    stays None; only the names a rule actually references need shipping."""
    st = h.__dict__.get("_fp")
    return {price_name(key): price for key, price in st.price.items()} if st else {}


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
                new = rule   # as built: interned, so an unchanged price is still the same object
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
