"""Exact equivalence of two logic rules: does one accept a state the other rejects, and which?

Compare two versions of a rule (e.g. before/after a logic change). Run directly for the self-test.

    python tools/rule_equivalence.py
"""

from audit_bare_rules import REPO_ROOT  # noqa: E402,F401  (sets up the AP import path)


# --- exact equivalence of two monotone rules ---------------------------------------------------------
#
# Rules only use AND / OR / atleast over has / region / loc leaves, so they are monotone: holding more
# never turns a rule false. For monotone F and G, "F implies G" can be decided without a SAT solver:
#   * expand F into alternatives (OR of ANDs). F => G iff every alternative, held on its own, satisfies G;
#   * or expand G into clauses (AND of ORs). F => G iff F fails at every "everything but this clause"
#     point.
# A `has(item, n)` leaf is a count, so one point gives each item a count rather than a yes/no.

_CAP = 20000   # alternatives / clauses kept per node before giving up on that expansion


class TooBig(Exception):
    pass


def _expand(node, mode, memo):
    """``mode="dnf"``: set of alternatives (frozensets of leaves, any one suffices).
    ``mode="cnf"``: set of clauses (frozensets of leaves, every clause needs one)."""
    key = id(node)
    if key in memo:
        return memo[key][1]
    kind = node["k"]
    if kind == "const":
        # True: dnf {∅} (one empty alternative), cnf ∅ (no clauses). False: the reverse.
        result = {frozenset()} if node["v"] == (mode == "dnf") else set()
    elif kind in ("has", "region", "loc"):
        leaf = ("has", node["i"], node["n"]) if kind == "has" else (kind, node.get("r") or node.get("l"))
        result = {frozenset([leaf])}
    elif kind == "atleast":
        result = _expand(_atleast(node["n"], node["c"]), mode, memo)
    else:
        children = [_expand(c, mode, memo) for c in node["c"]]
        union = (kind == "or") == (mode == "dnf")   # dnf: OR unions, AND crosses; cnf: the dual
        if union:
            result = set().union(*children)
        else:
            result = {frozenset()}
            for child in children:
                result = {a | b for a in result for b in child}
                if len(result) > _CAP:
                    raise TooBig
        result = _minimal(result)
    if len(result) > _CAP:
        raise TooBig
    memo[key] = (node, result)   # keep the node alive: a temporary atleast rewrite's id could be reused
    return result


def _atleast(n, children):
    """atleast(n, [c, *rest]) = (c AND atleast(n-1, rest)) OR atleast(n, rest), as plain and/or."""
    if n <= 0:
        return {"k": "const", "v": True}
    if n > len(children):
        return {"k": "const", "v": False}
    head, rest = children[0], children[1:]
    return {"k": "or", "c": [{"k": "and", "c": [head, _atleast(n - 1, rest)]}, _atleast(n, rest)]}


def _minimal(sets):
    """Drop any set that contains another (it adds nothing to an OR of ANDs, or an AND of ORs)."""
    kept = []
    for s in sorted(sets, key=len):
        if not any(k <= s for k in kept):
            kept.append(s)
    return set(kept)


def _holds(node, point, memo):
    """Evaluate a serialized rule at a point: {item: count, ("region", r)/("loc", l): bool}."""
    key = id(node)
    if key in memo:
        return memo[key]
    kind = node["k"]
    if kind == "const":
        result = node["v"]
    elif kind == "has":
        result = point.get(node["i"], 0) >= node["n"]
    elif kind in ("region", "loc"):
        result = point.get((kind, node.get("r") or node.get("l")), False)
    elif kind == "and":
        result = all(_holds(c, point, memo) for c in node["c"])
    elif kind == "or":
        result = any(_holds(c, point, memo) for c in node["c"])
    else:
        result = sum(_holds(c, point, memo) for c in node["c"]) >= node["n"]
    memo[key] = result
    return result


def _min_point(term):
    """The least you can hold and still have every leaf of an alternative."""
    point = {}
    for leaf in term:
        if leaf[0] == "has":
            point[leaf[1]] = max(point.get(leaf[1], 0), leaf[2])
        else:
            point[leaf] = True
    return point


class _Everything(dict):
    """A point holding everything except what a clause names: missing keys are 'plenty' / True."""
    def get(self, key, default=None):
        return dict.get(self, key, 10 ** 9 if isinstance(key, str) else True)


def _max_point(clause):
    point = _Everything()
    for leaf in clause:
        if leaf[0] == "has":
            point[leaf[1]] = min(point.get(leaf[1]), leaf[2] - 1)
        else:
            point[leaf] = False
    return point


def _via_alternatives(f, g):
    for term in _expand(f, "dnf", {}):
        point = _min_point(term)
        if not _holds(g, point, {}):
            return point
    return None


def _via_clauses(f, g):
    for clause in _expand(g, "cnf", {}):
        point = _max_point(clause)
        if _holds(f, point, {}):
            return point
    return None


_BUDGET = 200000   # search steps before _search gives up


def _atom(node):
    return ("has", node["i"], node["n"]) if node["k"] == "has" else (node["k"], node.get("r") or node.get("l"))


def _search(f, goals, falsified, budget):
    """Backtracking search for a point where F holds and every node in ``goals`` fails. Exact (it
    explores every way to falsify the goals) and pruned: a branch stops as soon as F fails at the
    most generous point it allows, since falsifying more can only make F fail harder."""
    goals = list(goals)
    while True:
        budget[0] -= 1
        if budget[0] < 0:
            raise TooBig
        point = _max_point(falsified)
        if not _holds(f, point, {}):
            return None
        while goals and not _holds(goals[-1], point, {}):
            goals.pop()   # already false here
        if not goals:
            return point
        node = goals.pop()
        kind = node["k"]
        if kind in ("has", "region", "loc"):
            falsified = falsified | {_atom(node)}
        elif kind == "or":
            goals.extend(node["c"])          # every option must fail
        elif kind == "atleast":
            goals.append(_atleast(node["n"], node["c"]))
        elif kind == "const":
            return None                      # a true constant can't be falsified
        else:                                # and: one failing part is enough — try each
            for child in node["c"]:
                found = _search(f, goals + [child], falsified, budget)
                if found is not None:
                    return found
            return None


def _first_counterexample(check, parts):
    """The first counterexample among split checks; a part too big to decide doesn't stop the others
    (one counterexample anywhere settles it), but with none found it leaves the answer undecided."""
    too_big = False
    for part in parts:
        try:
            point = check(part)
        except TooBig:
            too_big = True
            continue
        if point is not None:
            return point
    if too_big:
        raise TooBig
    return None


def implies(f, g, memo=None):
    """None if F => G, else a counterexample point (F holds there, G doesn't). Raises TooBig if
    no exact route fits under _CAP.

    Splits first, which is what keeps a 40-criterion advancement decidable; every split is exact:
      F => (g1 AND g2 ...)  iff  F => each gi       (a counterexample for one gi is one for G)
      (f1 OR f2 ...) => G   iff  each fi => G       (a point where fi holds is one where F holds)
      (f1 AND f2 ...) => G  if   some fi => G       (sufficient only, so it never reports a difference)
    """
    memo = {} if memo is None else memo
    key = (id(f), id(g))
    if key in memo:
        return memo[key]
    if f is g:
        result = None
    elif g["k"] == "and":
        result = _first_counterexample(lambda gi: implies(f, gi, memo), g["c"])
    elif f["k"] == "or":
        result = _first_counterexample(lambda fi: implies(fi, g, memo), f["c"])
    else:
        result = False   # undecided so far
        if f["k"] == "and":
            for fi in f["c"]:
                try:
                    if implies(fi, g, memo) is None:
                        result = None
                        break
                except TooBig:
                    pass
        if result is False:
            try:
                result = _via_alternatives(f, g)
            except TooBig:
                try:
                    result = _via_clauses(f, g)
                except TooBig:
                    result = _search(f, [g], frozenset(), [_BUDGET])
    memo[key] = result
    return result


def describe(point) -> str:
    if isinstance(point, _Everything):
        missing = [f"{k} at most {v}" if isinstance(k, str) else f"no {k[0]} {k[1]}" for k, v in point.items()
                   if not (isinstance(v, bool) and v)]
        return "everything except: " + ", ".join(sorted(missing))
    held = [f"{k} x{v}" if isinstance(k, str) else f"{k[0]} {k[1]}" for k, v in point.items()]
    return "holding only: " + (", ".join(sorted(held)) or "nothing")


def _subtrees(rule, out):
    if rule.key() not in out:
        out.add(rule.key())
        for child in getattr(rule, "children", ()):
            _subtrees(child, out)
    return out


def _abstract(rule, shared, memo):
    """The rule as a serialized dict, with every composite subtree that also occurs in the other
    rule replaced by one opaque leaf. Sound for proving equivalence (substituting equal parts for a
    shared leaf preserves it), not for disproving it, so a difference found here is re-checked on
    the full rules."""
    key = rule.key()
    if key in memo:
        return memo[key]
    children = getattr(rule, "children", None)
    if children is None:
        node = rule.to_dict()
    elif key in shared:
        node = {"k": "loc", "l": f"<shared #{len(memo)}>"}
    else:
        node = {**rule.to_dict(), "c": [_abstract(c, shared, memo) for c in children]}
    memo[key] = node
    return node


def _both_ways(f, g):
    return implies(g, f), implies(f, g)   # (b accepts / a rejects, a accepts / b rejects)


def _leaf_atoms(node, out, seen):
    if id(node) not in seen:
        seen.add(id(node))
        if node["k"] == "has":
            out.add(("has", node["i"], node["n"]))
        elif node["k"] in ("region", "loc"):
            out.add((node["k"], node.get("r") or node.get("l")))
        for child in node.get("c", ()):
            _leaf_atoms(child, out, seen)
    return out


def _cheap_alternative(node, memo):
    """One set of leaves that satisfies the rule, picking the smallest option at every OR (greedy, so
    not always the global minimum; only ever used to look for a counterexample)."""
    key = id(node)
    if key in memo:
        return memo[key]
    kind = node["k"]
    if kind == "has":
        result = frozenset([("has", node["i"], node["n"])])
    elif kind in ("region", "loc"):
        result = frozenset([(kind, node.get("r") or node.get("l"))])
    elif kind == "const":
        result = frozenset()   # a False constant can't be satisfied; the caller's _holds check fails it
    else:
        options = sorted((_cheap_alternative(c, memo) for c in node["c"]), key=len)
        take = {"and": len(options), "or": 1}.get(kind, node.get("n", 1))
        result = frozenset().union(*options[:take])
    memo[key] = result
    return result


def _direction(f, g):
    """None if F => G, a counterexample point if not, TooBig if undecided. When the exact routes
    are too big, a counterexample can still be found cheaply: everything held except one leaf."""
    try:
        return implies(f, g)
    except TooBig:
        point = _min_point(_cheap_alternative(f, {}))   # F holds here by construction
        if not _holds(g, point, {}):
            return point
        for atom in sorted(_leaf_atoms(f, set(), set()) | _leaf_atoms(g, set(), set()), key=str):
            point = _max_point({atom})
            if _holds(f, point, {}) and not _holds(g, point, {}):
                return point
        raise


def equivalence(a, b) -> tuple[str, str]:
    """('equivalent' | 'a stricter' | 'b stricter' | 'neither implies the other' | 'too big', detail)."""
    shared = (_subtrees(a, set()) & _subtrees(b, set())) - {a.key(), b.key()}
    memo: dict = {}
    try:
        if _both_ways(_abstract(a, shared, memo), _abstract(b, shared, memo)) == (None, None):
            return "equivalent", ""
    except TooBig:
        pass
    try:
        b_only = _direction(b.to_dict(), a.to_dict())
    except TooBig:
        b_only = TooBig
    try:
        a_only = _direction(a.to_dict(), b.to_dict())
    except TooBig:
        a_only = TooBig
    if b_only is TooBig or a_only is TooBig:
        known = a_only if b_only is TooBig else b_only
        if known is TooBig:
            return "too big to decide", ""
        if known is None:
            proven = "a implies b" if b_only is TooBig else "b implies a"
            return f"{proven}; reverse undecided", ""
        side = "a accepts, b rejects" if b_only is TooBig else "b accepts, a rejects"
        return "differ (other direction undecided)", f"{side} — {describe(known)}"
    if b_only is None and a_only is None:
        return "equivalent", ""
    if b_only is None:
        return "b stricter", f"a accepts, b rejects — {describe(a_only)}"
    if a_only is None:
        return "a stricter", f"b accepts, a rejects — {describe(b_only)}"
    return "neither implies the other", (f"b accepts, a rejects — {describe(b_only)}; "
                                         f"a accepts, b rejects — {describe(a_only)}")


def _selftest():
    has = lambda i, n=1: {"k": "has", "i": i, "n": n}  # noqa: E731
    reg = lambda r: {"k": "region", "r": r}  # noqa: E731
    a, b, c = has("a"), has("b"), has("c")
    # Distribution: a AND (b OR c) == (a AND b) OR (a AND c).
    f = {"k": "and", "c": [a, {"k": "or", "c": [b, c]}]}
    g = {"k": "or", "c": [{"k": "and", "c": [a, b]}, {"k": "and", "c": [a, c]}]}
    assert implies(f, g) is None and implies(g, f) is None
    # Counts: has(a, 2) implies has(a, 1), not the reverse.
    assert implies(has("a", 2), has("a", 1)) is None
    assert implies(has("a", 1), has("a", 2)) == {"a": 1}
    # atleast(2, a, b, c) == (a AND b) OR (a AND c) OR (b AND c).
    two = {"k": "atleast", "n": 2, "c": [a, b, c]}
    pairs = {"k": "or", "c": [{"k": "and", "c": [x, y]} for x, y in ((a, b), (a, c), (b, c))]}
    assert implies(two, pairs) is None and implies(pairs, two) is None
    # A region is a leaf like any other; AND is stricter than OR.
    assert implies({"k": "or", "c": [a, reg("Nether")]}, a) is not None
    # The clause route gives the same answers as the alternatives route.
    cases = [(f, g), (g, f), (two, pairs), (pairs, two), (has("a", 2), has("a", 1)),
             (has("a", 1), has("a", 2)), ({"k": "or", "c": [a, reg("Nether")]}, a),
             (a, {"k": "or", "c": [a, reg("Nether")]}), ({"k": "const", "v": True}, a),
             (a, {"k": "const", "v": True}), ({"k": "const", "v": False}, a)]
    for x, y in cases:
        expected = _via_alternatives(x, y) is None
        assert (_via_clauses(x, y) is None) == expected, (x, y)
        assert (_search(x, [y], frozenset(), [_BUDGET]) is None) == expected, (x, y)
    # equivalence() on real Rule nodes, through the shared-subtree shortcut.
    from worlds.minecraft_aem.logic.ast import Has, and_, or_
    x, y, z, w = (Has(1, i) for i in "xyzw")
    shared = or_(z, w)
    assert equivalence(and_(x, or_(y, shared)), or_(and_(x, y), and_(x, shared)))[0] == "equivalent"
    # Equal outside a shared part, unequal inside it: the full check must still catch it.
    assert equivalence(and_(x, or_(y, z)), and_(x, y))[0] == "b stricter"
    print("selftest ok")


if __name__ == "__main__":
    _selftest()
