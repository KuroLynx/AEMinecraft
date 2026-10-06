"""Does an item cost the same wherever it is needed — and when it doesn't, why not?

acquire() memoizes on (item, recursion stack, finding stack, pricing-trade guard, bulk), so the
diamond inside a jukebox and the diamond inside a diamond sword are priced separately. This compiles
every location, then compares each cached variant with the item priced on its own (empty stacks,
same bulk mode) using rule_equivalence's exact check.

    looser    the variant accepts a state the item on its own rejects: a LEAK (raw iron's 2026-10-06
              bug, where the cut stack fell through to a bare material tier)
    stricter  the variant rejects a state the item accepts
    dropped   the variant is None (route removed) while the item on its own is obtainable

Each difference gets every cause found in a trace of the acquire() calls under the variant,
joined with "+" (a recipe stops at its first dead ingredient, so one cause can hide another):

    cycle     a route was cut because it goes through an ancestor on the variant's stack. The game
              agrees: you can't use A to get the C you need for A. Usually legitimate.
    depth     the variant hit _MAX_DEPTH (the stack used up part of the budget). An arbitrary cutoff,
              not a game fact: a bug when it is the only cause.
    demote    somewhere under the variant, _demote kept flimsy routes because no dependable one
              survived, where the item on its own didn't. Follows from a cycle or depth cut above it.
    context   the finding stack / trade-pricing guard is set. Judge by hand.
    unexplained  none of the above (loose/dry marks shared by id(), the fallback…). Investigate.

    python tools/audit_route_consistency.py            # default world
    python tools/audit_route_consistency.py --bacap    # everything on + BACAP
"""
import sys
from collections import Counter, defaultdict

from audit_bare_rules import ALL_LOCKS  # noqa: E402  (also sets up the AP import path)
from rule_equivalence import equivalence  # noqa: E402

from worlds.AutoWorld import AutoWorldRegister  # noqa: E402
from test.general import setup_multiworld  # noqa: E402
from worlds.minecraft_aem.logic import acquisition, root  # noqa: E402

VERDICT = {"equivalent": "same", "a stricter": "looser", "b stricter": "stricter"}


def _traced(original):
    """Wrap RuleHelper.acquire to record, per cache key: child keys, cycle-cut items, depth-capped items.
    Mirrors acquire()'s own key and early returns (acquisition.py, `def acquire`)."""
    def acquire(self, item_id, _stack=frozenset()):
        t = self.__dict__.setdefault("_trace", {"path": [], "kids": defaultdict(set),
                                                "cuts": defaultdict(set), "caps": defaultdict(set)})
        base = item_id.split(":", 1)[-1]
        parent = t["path"][-1] if t["path"] else None
        if base.startswith("#"):
            return original(self, item_id, _stack)
        if base in _stack:
            t["cuts"][parent].add(base)
            return original(self, item_id, _stack)
        if len(_stack) >= self._MAX_DEPTH:
            t["caps"][parent].add(base)
            return original(self, item_id, _stack)
        key = (base, _stack, self._finding_stack, self._pricing_trade, self._bulk)
        t["kids"][parent].add(key)
        t["path"].append(key)
        try:
            return original(self, item_id, _stack)
        finally:
            t["path"].pop()
    return acquire


def _subtree(trace, loose_keys, key, memo):
    """(cycle-cut items, depth-capped items, items that kept flimsy routes) anywhere under ``key``.
    The key graph is acyclic: a child's stack is strictly larger than its parent's."""
    if key not in memo:
        cuts, caps = set(trace["cuts"][key]), set(trace["caps"][key])
        loose = {key[0]} if key in loose_keys else set()
        for kid in trace["kids"][key]:
            c, d, f = _subtree(trace, loose_keys, kid, memo)
            cuts |= c
            caps |= d
            loose |= f
        memo[key] = frozenset(cuts), frozenset(caps), frozenset(loose)
    return memo[key]


def _cause(h, ckey, rkey, memo):
    _, stack, finding, pricing, _ = ckey
    cuts, caps, loose = _subtree(h._trace, h._loose_keys, ckey, memo)
    _, _, ref_loose = _subtree(h._trace, h._loose_keys, rkey, memo)
    found = {
        # cut by the CONTEXT, not by the item's own internal cycles
        "cycle": sorted(cuts & stack),
        # any cap counts: the stack used up part of the budget, so the item on its own hits it
        # later on that branch or not at all
        "depth": sorted(caps),
        # _demote kept flimsy routes because nothing dependable survived, where the item on its own
        # didn't need to
        "demote": sorted(loose - ref_loose),
    }
    if finding or pricing:
        found["context"] = ["finding " + ",".join(sorted(finding))] if finding else ["pricing trade"]
    hit = [name for name, items in found.items() if items]
    return "+".join(hit) or "unexplained", "; ".join(f"{n}: {', '.join(found[n])}" for n in hit)


def audit(options):
    helpers = []
    init, acquire = acquisition.RuleHelper.__init__, acquisition.RuleHelper.acquire

    def capture(self, *a, **kw):
        init(self, *a, **kw)
        helpers.append(self)

    acquisition.RuleHelper.__init__ = capture
    acquisition.RuleHelper.acquire = _traced(acquire)
    try:
        world = setup_multiworld(AutoWorldRegister.world_types["AEMinecraft"], seed=12345,
                                 options=options).worlds[1]
        root.build_location_rules(world)
        h = next(x for x in helpers if not x.glitch)

        variants = defaultdict(dict)    # (base, bulk) -> {result key: (result, example cache key)}
        for key, result in list(h._acquire_cache.items()):
            base, stack, finding, pricing, bulk = key
            if stack or finding or pricing:
                variants[(base, bulk)].setdefault(result.key() if result is not None else None, (result, key))

        rows, memo = [], {}
        for (base, bulk), seen in sorted(variants.items()):
            with h.bulk_mode(bulk):
                ref = h.acquire(f"minecraft:{base}")
            rkey = (base, frozenset(), frozenset(), h._pricing_trade, bulk)
            for result_key, (result, ckey) in seen.items():
                if ref is None or (result is not None and result_key == ref.key()):
                    continue
                if result is None:
                    verdict, detail = "dropped", ""
                else:
                    v, detail = equivalence(ref, result)
                    verdict = VERDICT.get(v, v)
                    detail = (detail.replace("b accepts, a rejects", "only the variant accepts")
                              .replace("a accepts, b rejects", "only the item alone accepts"))
                if verdict != "same":
                    cause, why = _cause(h, ckey, rkey, memo)
                    rows.append((verdict, cause, base, bulk, ckey, why, detail))
    finally:
        acquisition.RuleHelper.__init__, acquisition.RuleHelper.acquire = init, acquire
    return rows


def main(argv):
    sys.stdout.reconfigure(encoding="utf-8")
    options = {**ALL_LOCKS, "blazeandcave": 1} if "--bacap" in argv else {}
    rows = audit(options)

    counts = Counter((r[0], r[1]) for r in rows)
    causes = sorted({r[1] for r in rows})
    print("| verdict | " + " | ".join(causes) + " |")
    print("|---" * (len(causes) + 1) + "|")
    for verdict in sorted({r[0] for r in rows}, key=lambda v: (v != "looser", v)):
        print(f"| {verdict} | " + " | ".join(str(counts[verdict, c]) for c in causes) + " |")

    by = defaultdict(list)
    for row in rows:
        by[row[0], row[1]].append(row)
    for verdict, cause in sorted(by, key=lambda vc: (vc[0] != "looser", vc)):
        group = by[verdict, cause]
        print(f"\n## {verdict} / {cause}: {len(group)} variants, {len({r[2] for r in group})} items")
        for _, _, base, bulk, (_, stack, finding, pricing, _), why, detail in group:
            ctx = ", ".join(sorted(stack)) + (" | finding " + ",".join(sorted(finding)) if finding else "") \
                + (" | pricing trade" if pricing else "")
            print(f"- {base}{' (bulk)' if bulk else ''} inside [{ctx}]"
                  + (f" — {why}" if why else "") + (f" — {detail}" if detail else ""))


if __name__ == "__main__":
    main(sys.argv[1:])
