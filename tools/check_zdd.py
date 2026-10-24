"""Self-check for logic/fixed_point.py's ZDD canonical form: random rules, and canonical(rule) must
accept exactly the states rule accepts (every state of a small universe), and be the same object for
two spellings of the same logic.

    python tools/check_zdd.py
"""
import itertools
import os
import random
import sys

AP_ROOT = r"C:\Users\benja\PycharmProjects\ArchipelagoClone"
sys.path.insert(0, AP_ROOT)
os.environ.setdefault("SKIP_REQUIREMENTS_UPDATE", "1")

from worlds.minecraft_aem.logic import fixed_point as fp  # noqa: E402
from worlds.minecraft_aem.logic.ast import Has, ReachRegion, and_, at_least, or_  # noqa: E402

ITEMS, REGIONS, MAX = ("a", "b", "c"), ("X", "Y"), 3


class State:
    def __init__(self, counts, regions):
        self.prog_items = {1: dict(zip(ITEMS, counts))}
        self.regions = regions

    def can_reach_region(self, region, _player):
        return region in self.regions


STATES = [State(counts, {r for r, on in zip(REGIONS, bits) if on})
          for counts in itertools.product(range(MAX + 1), repeat=len(ITEMS))
          for bits in itertools.product((False, True), repeat=len(REGIONS))]


def rand_rule(rng, depth):
    if depth == 0 or rng.random() < 0.3:
        if rng.random() < 0.7:
            return Has(1, rng.choice(ITEMS), rng.randint(1, MAX))
        return ReachRegion(1, rng.choice(REGIONS))
    kids = [rand_rule(rng, depth - 1) for _ in range(rng.randint(2, 4))]
    kind = rng.random()
    if kind < 0.4:
        return and_(*kids)
    if kind < 0.8:
        return or_(*kids)
    return at_least(rng.randint(1, len(kids)), kids)


def main():
    rng = random.Random(7)
    fp._reset()
    for _ in range(2000):
        rule = rand_rule(rng, 3)
        canon = fp.canonical(1, rule)
        for state in STATES:
            assert rule(state) == canon(state), (rule.to_dict(), canon.to_dict())
        # Same logic, other spelling (an absorbed extra alternative): the very same node.
        assert fp.canonical(1, or_(canon, and_(canon, rand_rule(rng, 1)))) is canon
    print("check_zdd: OK")


if __name__ == "__main__":
    main()
