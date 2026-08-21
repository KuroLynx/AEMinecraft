"""Which predicate fields the trigger compiler actually READS, and which it silently drops.

The bare-rule audit (``audit_bare_rules.py``) catches a criterion that compiles to nothing at all.
It cannot catch the subtler and more common failure: a criterion that compiles to *something*, so it
looks handled, while part of its predicate was never looked at. ``The Actual End`` is the shape —
``entity: {type: enderman, location: {dimension: the_end}}`` compiles to a perfectly respectable
``entity(Enderman)`` gate, and the End requirement is simply gone. A gated-looking rule hides it
from every existing check.

So this measures coverage directly instead of inferring it from the result. Every criterion's
``conditions`` is wrapped in dict/list subclasses that record each key the compiler touches
(``get`` / ``[]`` / ``in`` / ``items`` / ``values``), then ``TriggerCompiler._criterion`` is run on
it. Diffing the recorded reads against every leaf path in the predicate yields, exactly, the fields
that had no influence on the rule.

Paths are normalised (list indices -> ``[]``) and aggregated, because the judgement belongs to the
FIELD, not the advancement: ``entity.location.dimension`` is one decision covering 26 checks. Each
row is one such decision.

Unread is not automatically a bug — plenty of predicate fields carry no logic (a `condition` type
tag, an `nbt` blob, a `distance` bound, `flags`, cosmetic `type_specific` details). The report does
NOT decide that for you: it lists every unread field and leaves the call to the reader, because a
field quietly filtered onto a "harmless" list is a field nobody looks at again.

Run from anywhere:
    python tools/audit_predicate_coverage.py                  # BACAP, unhandled paths
    python tools/audit_predicate_coverage.py --vanilla        # the vanilla pack instead
    python tools/audit_predicate_coverage.py --all-rows       # also list fully-read paths
    python tools/audit_predicate_coverage.py --examples 6     # more example advancements per row
    python tools/audit_predicate_coverage.py --markdown --out docs/predicate_coverage.md
"""
import os

import sys
from collections import defaultdict
from contextlib import redirect_stdout

AP_ROOT = r"C:\Users\benja\PycharmProjects\ArchipelagoClone"
REPO_ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
sys.path.insert(0, AP_ROOT)
os.chdir(AP_ROOT)

from worlds.AutoWorld import AutoWorldRegister  # noqa: E402
from test.general import setup_multiworld  # noqa: E402
from worlds.minecraft_aem.content.registry import base_pack, overlay_packs  # noqa: E402
from worlds.minecraft_aem.logic.acquisition import RuleHelper  # noqa: E402
from worlds.minecraft_aem.logic.root import _manifest  # noqa: E402
from worlds.minecraft_aem.logic.triggers import TriggerCompiler  # noqa: E402

WORLD = AutoWorldRegister.world_types["Minecraft [AEM]"]

# Every lock on, so a field's gate can't read as absent merely because this seed put no item behind
# it. Coverage is a property of the compiler, not of a config, but the helper still consults options.
ALL_LOCKS = {
    "blazeandcave": 1, "bacap_rewards": 0, "challenge_sanity": 1, "kill_sanity": 1,
    "villager_trust": 1, "mob_spawn_lock": ["All"], "structure_unlock": ["All"],
    "knowledge_gates": ["All"], "boss_list": ["All"],
}


# NOTE: this report deliberately does NOT filter. Whether a given field carries a gate is a
# judgement the report has no business making silently — an entry on a "harmless" list is invisible
# afterwards, and a wrong one hides a real leak forever. So every unread field is listed, and the
# non-gating call is left to the reader.


def _read_key(leaf: tuple) -> tuple:
    """The access that would prove ``leaf`` was consumed.

    Every real read goes through the parent dict with the leaf's own key, so a leaf counts only when
    its EXACT path was touched. Fetching an ancestor container is not consumption: ``_entity_node``
    does ``cond.get("entity")`` and then looks only at ``type``, so crediting everything under
    ``entity`` would have declared ``entity.location.dimension`` covered — the very leak that
    prompted this audit.

    List traversal is the one transparent step: iterating a list is not tracked, so trailing ``[]``
    components are stripped and ``item.items[]`` is credited to the ``item.items`` read that produced
    the list."""
    end = len(leaf)
    while end > 0 and leaf[end - 1] == "[]":
        end -= 1
    return leaf[:end]


def _verdict(leaf: tuple, seen: dict) -> str:
    """``read`` / ``coarse`` / ``dropped`` for one leaf.

    An unread leaf has two very different causes, and telling them apart is what makes the report
    actionable. The access KIND settles it, which is why the tracker records it:

    ``coarse``  the nearest ancestor the compiler touched was touched by a *presence test*
                (``"enchantments" in predicates``). Testing existence IS consuming the branch: any
                enchantment costs the same enchanting-table gate, so which one it is changes nothing.
                Deliberate — but only sound while that premise holds, and it does not for Swift
                Sneak, which no enchanting table offers.
    ``dropped`` the nearest ancestor was *fetched* (``cond.get("entity")``) and the compiler then
                descended into some other key, leaving this branch unexamined. ``_entity_node`` reads
                ``entity.type`` and never ``entity.location``, so every dimension under it vanished.
    """
    key = _read_key(leaf)
    if key in seen:
        return "read"
    probe = key[:-1]
    while probe:
        probe = _read_key(probe)
        if probe in seen:
            return "coarse" if seen[probe] == {"contains"} else "dropped"
        probe = probe[:-1]
    return "dropped"


def _norm(path: tuple) -> str:
    out = ""
    for part in path:
        if part == "[]":
            out += "[]"
        else:
            out += ("." if out else "") + str(part)
    return out


class _TrackedDict(dict):
    """A dict that records which of its keys the compiler looked at, and HOW.

    The kind matters: ``in`` tests existence (the branch is consumed as a whole), while ``get`` /
    ``[]`` fetch the value to descend into it. ``_verdict`` needs that distinction to separate a
    deliberate coarsening from a dropped branch."""

    def __init__(self, data, path, seen):
        super().__init__()
        self._path, self._seen = path, seen
        for key, value in data.items():
            super().__setitem__(key, _wrap(value, path + (key,), seen))

    def _mark(self, key, kind):
        self._seen.setdefault(self._path + (key,), set()).add(kind)

    def get(self, key, default=None):
        self._mark(key, "get")
        return super().get(key, default)

    def __getitem__(self, key):
        self._mark(key, "get")
        return super().__getitem__(key)

    def __contains__(self, key):
        self._mark(key, "contains")
        return super().__contains__(key)

    def items(self):
        for key, value in super().items():
            self._mark(key, "get")
            yield key, value

    def values(self):
        for key, value in super().items():
            self._mark(key, "get")
            yield value

    def __iter__(self):
        # `for k in d` and `d.keys()` both land here. Reading the key names IS how a handler decides
        # a branch applies (_entity_variant_node looks for any key ending in "/variant"), so without
        # this the audit reported a gate it had itself failed to observe.
        for key in super().__iter__():
            self._mark(key, "contains")
            yield key

    def keys(self):
        return iter(self)


class _TrackedList(list):
    def __init__(self, data, path, seen):
        super().__init__(_wrap(v, path + ("[]",), seen) for v in data)


def _wrap(value, path, seen):
    if isinstance(value, dict):
        return _TrackedDict(value, path, seen)
    if isinstance(value, list):
        return _TrackedList(value, path, seen)
    return value


def _leaves(value, path=()):
    """Every path to a scalar (or empty container) inside ``value``."""
    if isinstance(value, dict) and value:
        for key, sub in value.items():
            yield from _leaves(sub, path + (key,))
    elif isinstance(value, list) and value:
        for sub in value:
            yield from _leaves(sub, path + ("[]",))
    else:
        yield path


def audit(pack: str):
    world = setup_multiworld(WORLD, options=ALL_LOCKS).worlds[1]
    helper = RuleHelper(world)
    compiler = TriggerCompiler(helper, frozenset(world._get_active_locations()))
    manifest = _manifest(pack)

    # normalised path -> per-verdict counts + the triggers/advancements it was dropped under
    stats = defaultdict(lambda: {"read": 0, "coarse": 0, "dropped": 0,
                                 "triggers": set(), "examples": set()})
    compiled = dropped = 0

    for record in manifest.values():
        if not isinstance(record, dict):
            continue
        title = record.get("title") or "?"
        for crit in (record.get("criteria") or {}).values():
            if not isinstance(crit, dict):
                continue
            conditions = crit.get("conditions") or {}
            trigger = str(crit.get("trigger", "")).split(":")[-1]
            seen = {}
            tracked = _wrap(conditions, (), seen)
            probe = dict(crit)
            probe["conditions"] = tracked
            try:
                rule = compiler._criterion(probe)
            except Exception as exc:                      # a handler crash is itself a finding
                print(f"  !! {title} / {trigger}: {type(exc).__name__}: {exc}")
                continue
            compiled += rule is not None
            dropped += rule is None
            for leaf in _leaves(conditions):
                if not leaf:
                    continue
                row = stats[_norm(leaf)]
                verdict = _verdict(leaf, seen)
                row[verdict] += 1
                if verdict == "dropped":
                    row["triggers"].add(trigger)
                    row["examples"].add(title)
    return stats, compiled, dropped


def report(stats, compiled, dropped, *, pack, all_rows, examples, markdown):
    unhandled = [(p, r) for p, r in stats.items() if r["dropped"]]
    coarse = [(p, r) for p, r in stats.items() if r["coarse"] and not r["dropped"]]
    read_only = [(p, r) for p, r in stats.items() if not r["dropped"] and not r["coarse"]]

    def table(title, rows, note, key="dropped"):
        rows = sorted(rows, key=lambda kv: (-kv[1][key], kv[0]))
        if markdown:
            print(f"\n## {title} — {len(rows)}\n\n{note}\n")
            print("| Predicate path | Dropped | Coarsened | Read | Triggers | Examples |")
            print("|---" * 6 + "|")
            for path, row in rows:
                ex = ", ".join(sorted(row["examples"])[:examples]) or "—"
                print(f"| `{path}` | {row['dropped']} | {row['coarse']} | {row['read']} | "
                      f"{','.join(sorted(row['triggers'])) or '—'} | {ex} |")
        else:
            print(f"\n=== {title} — {len(rows)}")
            print(f"    {note}")
            for path, row in rows:
                print(f"  {row['dropped']:5d} dropped {row['coarse']:5d} coarse "
                      f"{row['read']:5d} read   {path}")
                if row["triggers"]:
                    print(f"         triggers: {','.join(sorted(row['triggers']))}")
                if row["examples"]:
                    print(f"         e.g. {', '.join(sorted(row['examples'])[:examples])}")

    if markdown:
        print(f"# Predicate coverage — `{pack}`\n")
        print("Generated by `tools/audit_predicate_coverage.py`. Every criterion's `conditions` is "
              "wrapped so each key the compiler reads is recorded, then `TriggerCompiler._criterion` "
              "runs on it; a leaf no read ever touched had no influence on the compiled rule.\n")
        print(f"- criteria that produced a rule: **{compiled}**")
        print(f"- criteria that produced nothing: **{dropped}**")
        print(f"- distinct predicate paths: **{len(stats)}**\n")
    else:
        print(f"pack: {pack}")
        print(f"criteria compiled: {compiled}   compiled to nothing: {dropped}")
        print(f"distinct predicate paths: {len(stats)}")

    table("NOT HANDLED — the branch was never looked at", unhandled,
          "Every unread field, unfiltered. Each row is one decision in the compiler, not one bug per "
          "advancement. `Dropped` counts criteria where neither the field nor its container was read, "
          "so the branch had no chance to influence the rule. A non-zero `Read`/`Coarsened` beside it "
          "means a handler exists but misses this shape, which is usually the cheapest kind to fix. "
          "Judging which of these carry a gate is the reader's job — nothing is filtered out here.")
    table("NOT HANDLED — coarsened, detail ignored", coarse,
          "The compiler tested that the container exists and deliberately stopped there. Correct "
          "whenever the detail costs the same as the container (any enchantment needs the same "
          "enchanting table) — and a leak whenever it does not (Swift Sneak is in no enchanting "
          "table at all, so 'some enchantment' is the wrong price for it).")
    if all_rows:
        table("FULLY READ", read_only, "Every occurrence was consumed by a handler.", key="read")
    else:
        print(f"\n({len(read_only)} fully-read paths hidden — pass --all-rows)")


def main() -> int:
    pack = base_pack() if "--vanilla" in sys.argv else \
        (overlay_packs().get("blazeandcave") or base_pack())
    examples = 3
    if "--examples" in sys.argv:
        examples = int(sys.argv[sys.argv.index("--examples") + 1])
    out = None
    if "--out" in sys.argv:
        out = os.path.join(REPO_ROOT, sys.argv[sys.argv.index("--out") + 1])
    markdown = "--markdown" in sys.argv
    stats, compiled, dropped = audit(pack)
    args = dict(pack=pack, all_rows="--all-rows" in sys.argv, examples=examples, markdown=markdown)
    if out:
        with open(out, "w", encoding="utf-8", newline="\n") as fh:
            with redirect_stdout(fh):
                report(stats, compiled, dropped, **args)
        print(f"wrote {out}")
    else:
        report(stats, compiled, dropped, **args)
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
