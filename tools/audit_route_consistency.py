"""Does an item cost the same wherever it is needed?

acquire() memoizes on (item, recursion stack, finding stack, pricing-trade guard, bulk), so the
diamond inside a jukebox and the diamond inside a diamond sword are priced separately. They should
come out equivalent: the stack only exists to cut cycles, and a cycle-cut route is circular anyway.
This compiles every location, then compares each cached variant with the item priced on its own
(empty stacks, same bulk mode) using rule_equivalence's exact check.

    looser    the variant accepts a state the item on its own rejects: a LEAK (raw iron's 2026-10-06
              bug, where the cut stack fell through to a bare material tier)
    stricter  the variant rejects a state the item accepts: a cut that lost a non-circular route
    dropped   the variant is None (route removed) while the item on its own is obtainable

    python tools/audit_route_consistency.py            # default world
    python tools/audit_route_consistency.py --bacap    # everything on + BACAP
"""
import sys
from collections import defaultdict

from audit_bare_rules import ALL_LOCKS  # noqa: E402  (also sets up the AP import path)
from rule_equivalence import equivalence  # noqa: E402

from worlds.AutoWorld import AutoWorldRegister  # noqa: E402
from test.general import setup_multiworld  # noqa: E402
from worlds.minecraft_aem.logic import acquisition, root  # noqa: E402

VERDICT = {"equivalent": "same", "a stricter": "looser", "b stricter": "stricter"}


def audit(options):
    helpers = []
    init = acquisition.RuleHelper.__init__

    def capture(self, *a, **kw):
        init(self, *a, **kw)
        helpers.append(self)

    acquisition.RuleHelper.__init__ = capture
    try:
        world = setup_multiworld(AutoWorldRegister.world_types["AEMinecraft"], seed=12345,
                                 options=options).worlds[1]
        root.build_location_rules(world)
    finally:
        acquisition.RuleHelper.__init__ = init
    h = next(x for x in helpers if not x.glitch)

    variants = defaultdict(dict)    # (base, bulk) -> {result key: (result, example cache key)}
    for key, result in list(h._acquire_cache.items()):
        base, stack, finding, pricing, bulk = key
        if stack or finding or pricing:
            variants[(base, bulk)].setdefault(result.key() if result is not None else None, (result, key))

    rows = []
    for (base, bulk), seen in sorted(variants.items()):
        with h.bulk_mode(bulk):
            ref = h.acquire(f"minecraft:{base}")
        for rkey, (result, ckey) in seen.items():
            if ref is None or (result is not None and rkey == ref.key()):
                continue
            if result is None:
                verdict, detail = "dropped", ""
            else:
                v, detail = equivalence(ref, result)
                verdict = VERDICT.get(v, v)
            if verdict != "same":
                rows.append((verdict, base, bulk, ckey, detail))
    return rows


def main(argv):
    options = {**ALL_LOCKS, "blazeandcave": 1} if "--bacap" in argv else {}
    rows = audit(options)
    by = defaultdict(list)
    for row in rows:
        by[row[0]].append(row)
    for verdict in sorted(by, key=lambda v: (v != "looser", v)):
        items = sorted({r[1] for r in by[verdict]})
        print(f"\n## {verdict}: {len(by[verdict])} variants, {len(items)} items")
        for _, base, bulk, (_, stack, finding, pricing, _), detail in by[verdict]:
            ctx = ", ".join(sorted(stack)) + (" | finding " + ",".join(sorted(finding)) if finding else "") \
                + (" | pricing trade" if pricing else "")
            print(f"- {base}{' (bulk)' if bulk else ''} inside [{ctx}] {detail}")


if __name__ == "__main__":
    main(sys.argv[1:])
