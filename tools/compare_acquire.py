"""Recursive acquire() vs the fixed-point prototype (AEM_ACQUIRE=fixed): which location rules change?

Builds every location rule (strict graph) both ways in one process and checks each pair with
rule_equivalence's exact test. "looser" = the fixed point accepts a state the recursive version
rejects; read those first, they are where the recursion was stricter than the game or the fixed
point lost a gate.

    python tools/compare_acquire.py            # default world
    python tools/compare_acquire.py --bacap    # everything on + BACAP
"""
import sys
import time
from collections import Counter

from audit_bare_rules import ALL_LOCKS  # noqa: E402  (also sets up the AP import path)
from rule_equivalence import equivalence  # noqa: E402

from worlds.AutoWorld import AutoWorldRegister  # noqa: E402
from test.general import setup_multiworld  # noqa: E402
from worlds.minecraft_aem.logic import acquisition, root  # noqa: E402

VERDICT = {"equivalent": "same", "a stricter": "looser", "b stricter": "stricter"}


def build(world, fixed: bool):
    acquisition._FIXED_POINT = fixed
    start = time.time()
    rules = root.build_location_rules(world)
    return rules, time.time() - start


def main(argv):
    sys.stdout.reconfigure(encoding="utf-8")
    options = {**ALL_LOCKS, "blazeandcave": 1} if "--bacap" in argv else {}
    world = setup_multiworld(AutoWorldRegister.world_types["AEMinecraft"], seed=12345,
                             options=options).worlds[1]
    old, t_old = build(world, False)
    new, t_new = build(world, True)
    size = lambda rules: sum(r.serialized_size() for r in rules.values()) / 1024  # noqa: E731
    print(f"recursive {t_old:.1f}s {size(old):.0f} KB | fixed point {t_new:.1f}s {size(new):.0f} KB")

    rows = []
    for name in sorted(old):
        a, b = old[name], new.get(name)
        if b is None or a is b:
            continue
        verdict, detail = equivalence(a, b)
        verdict = VERDICT.get(verdict, verdict)
        if verdict != "same":
            detail = (detail.replace("b accepts, a rejects", "only the fixed point accepts")
                      .replace("a accepts, b rejects", "only the recursion accepts"))
            rows.append((verdict, name, detail))
    print(dict(Counter(r[0] for r in rows)))
    for verdict in sorted({r[0] for r in rows}, key=lambda v: (v != "looser", v)):
        group = [r for r in rows if r[0] == verdict]
        print(f"\n## {verdict}: {len(group)}")
        for _, name, detail in group:
            print(f"- {name} — {detail}")


if __name__ == "__main__":
    main(sys.argv[1:])
