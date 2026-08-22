"""Every check whose compiled rule asks for nothing — the "bare region test" audit.

The recurring bug class fixed in batches through v0.3.0: an advancement's criteria compile to
nothing meaningful, so its access rule is just ``Region(Overworld)`` (or ``Const(True)``) and the
tile reads green on a bare world. Six were fixed in v0.3.0; this script lists what is left so each
can be verified by hand.

The test is exact, not a heuristic: every rule node reports ``gate_summary() -> (gated, regions)``
bottom-up, where ``gated`` is True iff some leaf is a real gate (``has`` an item, or ``loc`` reach
another check). A rule with ``gated == False`` demands no item and no other check — only, at most,
standing in a dimension. That is the whole bug.

But "gates nothing" alone is NOT the bug, and this is the distinction the report exists to draw.
`Kelp me!` compiles its `inventory_changed` criterion perfectly into ``acquire(minecraft:kelp)``,
and kelp costs nothing but being in an ocean, so reducing to a region is the correct answer.
`Sweet Dreams` reduces to the same shape for a different reason: ``slept_in_bed`` *has* a handler,
but the handler returns a bare ``access_region(Overworld)`` on the reasoning that "a bed needs wool
+ planks" — and then never asks for either. One is right, one is a leak; they are indistinguishable
by shape, so the report classifies by cause and leaves the judgement to a human.

Flagged checks are bucketed by where the rule came from, worst first:

  UNCOMPILED — no handler read any criterion, no curated rule, no usable parent: the rule fell
               through to ``Const(True)``. Fix by adding a handler to ``logic/triggers.py``.
  CURATED    — a hand-written rule in ``logic/acquisition.py`` that itself gates nothing.
  KILL       — mob/boss kill checks, built by ``collect_entity_rules`` rather than from criteria.
  UNGATED    — the criteria compiled, but every node they produced is free. Where the judgement is.
  EXPECTED   — tab roots, dimension-entry checks gated on the region EDGE (see REGION_IS_THE_CHECK),
               and genuinely free checks. Not reported as suspect.

Each criterion is diagnosed as ``unreadable`` (no handler) or ``handler-free`` (a handler that
returned something free), and entries are grouped by that, because the fix belongs to the trigger
handler rather than the individual advancement — one handler usually clears its whole group.

One caveat drives the CASES matrix: a gate only exists if some option put an AP item behind it, so
a check can read "ungated" merely because the run has nothing to demand. The ``everything on`` case
turns every lock on; only checks still flagged there are ungated on their own merits.

Run from anywhere:
    python tools/audit_bare_rules.py                       # default config
    python tools/audit_bare_rules.py --all                 # every config in CASES
    python tools/audit_bare_rules.py --glitch              # the permissive display graph too
    python tools/audit_bare_rules.py --all --verbose       # include each serialized rule
    python tools/audit_bare_rules.py --all --markdown --out docs/bare_rule_backlog.md
"""
import json
import os
import sys
from collections import defaultdict
from contextlib import redirect_stdout

AP_ROOT = r"C:\Users\benja\PycharmProjects\ArchipelagoClone"
# Captured BEFORE the chdir below: generation must run from the AP clone, so a relative --out would
# otherwise resolve there instead of into this repo (which silently wrote the doc to the wrong tree).
REPO_ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
sys.path.insert(0, AP_ROOT)
os.chdir(AP_ROOT)

from worlds.AutoWorld import AutoWorldRegister  # noqa: E402
from test.general import setup_multiworld  # noqa: E402
from worlds.minecraft_aem.data import ADVANCEMENT_LOCATIONS  # noqa: E402
from worlds.minecraft_aem.content.registry import base_pack, overlay_packs  # noqa: E402
from worlds.minecraft_aem.logic.acquisition import RuleHelper  # noqa: E402
from worlds.minecraft_aem.logic.constants import (  # noqa: E402
    ADVANCEMENT_PREFIX, BOSS_KILL_PREFIX, ENTITY_KILL_PREFIX,
)
from worlds.minecraft_aem.logic.engine import collect_advancement_rules  # noqa: E402
from worlds.minecraft_aem.logic.triggers import TriggerCompiler  # noqa: E402
from worlds.minecraft_aem.logic.root import (  # noqa: E402
    _manifest, build_location_rules, derive_location_regions,
)

WORLD = AutoWorldRegister.world_types["AEMinecraft"]

# Checks that legitimately ask for nothing: you can do them the moment you spawn. Listed so the
# report stays short and every remaining line is a real question. Roots are detected structurally.
FREE_BY_DESIGN = {
    "Advancement: Minecraft",
    "Advancement: A Seedy Place",
    "Advancement: Caves & Cliffs",
    "Advancement: Sneak 100",
    "Advancement: The Parrots and the Bats",
}

# Checks whose entire content IS "get into this dimension". The gate is real but lives on the region
# EDGE, not on the location: Overworld->Nether wants the Dimension Unlock item plus obsidian (via
# Ice Bucket Challenge), Overworld->The End wants its unlock item, a stronghold and Eyes of Ender.
# A region-only rule is the correct compilation, so flagging these is a false positive.
#
# This exemption is only sound because `RuleHelper.enter_dimension` handles the start dimension: on
# a Nether start "We Need to Go Deeper" pins BOTH regions (you must leave and come back), so its
# region set is still the whole gate. If that ever regresses to a single free region, the check
# becomes genuinely ungated and this entry would hide it — the reason the audit prints the region
# set for every finding rather than just the name.
REGION_IS_THE_CHECK = {
    "Advancement: We Need to Go Deeper",
    "Advancement: The End?",
}

# The decisive setting. A gate only exists if some option put an AP item behind it, so a check can
# look "ungated" merely because the run has no such item to demand. With every lock ON, anything
# still asking for nothing is genuinely ungated — that is the real backlog. Auditing with locks off
# is what makes the list explode with false positives.
ALL_LOCKS = {
    "challenge_sanity": 1, "kill_sanity": 1, "villager_trust": 1,
    "mob_spawn_lock": {"passive", "neutral", "hostile", "boss"},
    "structure_unlock": {"All"}, "knowledge_gates": {"All"}, "boss_list": {"All"},
}

CASES = {
    "default (overworld start)": {},
    "nether start": {"start_dimension": "nether"},
    "everything on": dict(ALL_LOCKS),
    "everything on + BACAP": {**ALL_LOCKS, "blazeandcave": 1},
}


def _is_root(location_name: str, record: dict | None) -> bool:
    """A tab's root advancement: no parent, and the tab entry point. Carries no criteria worth
    compiling — reaching the dimension IS the check — so a bare rule is correct here."""
    if record is not None:
        return record.get("parent") is None
    return location_name.rsplit(":", 1)[-1].strip().lower() in {"minecraft", "root"}


def _triggers(record: dict | None) -> list[str]:
    """The distinct trigger ids across a record's criteria — what the compiler had to work with."""
    if not record:
        return []
    seen = []
    for crit in record.get("criteria", {}).values():
        trigger = crit.get("trigger", "?").removeprefix("minecraft:")
        if trigger not in seen:
            seen.append(trigger)
    return seen


def _diagnose(compiler, record: dict | None) -> list[str]:
    """Per-criterion diagnosis, as ``"<trigger> (<why>)"`` strings.

    If the whole rule gates nothing then no criterion produced a gating node, and there are exactly
    two ways for that to happen — worth separating, because they are fixed in different places:

      unreadable    — ``_criterion`` returned None. The trigger has no handler at all, so the
                      record failed to compile and the cascade fell through. Fix: add a handler.
      handler-free  — a handler exists but its node gates nothing. Sometimes right (``acquire`` of
                      a genuinely free item like kelp), sometimes a deliberate punt that has since
                      become wrong — ``slept_in_bed`` returns a bare ``access_region(Overworld)``
                      on the grounds that "a bed needs wool + planks", never requiring either.
                      Fix: tighten the handler. This is the one a human has to judge.
    """
    if not record:
        return []
    out = []
    for crit in record.get("criteria", {}).values():
        trigger = (crit.get("trigger") or "?").removeprefix("minecraft:")
        node = compiler._criterion(crit)
        why = "unreadable" if node is None else "handler-free"
        label = f"{trigger} ({why})"
        if label not in out:
            out.append(label)
    return out


def _provenance(compiler, record: dict | None, short: str, curated: dict) -> str:
    """Which branch of build_location_rules' cascade produced this rule. Mirrors that function
    exactly — if the cascade there changes, this must follow, or the buckets lie."""
    if record is not None and compiler.compile(record) is not None:
        return "UNGATED"  # compiled, but everything it compiled to is free
    if curated.get(short) is not None:
        return "CURATED"
    # A parent-chain rule contains a `loc` leaf, so it is gated and never reaches this audit;
    # anything left here fell all the way through to Const(True).
    return "UNCOMPILED"


def audit(world, *, glitch: bool) -> list[dict]:
    """Every location this seed whose rule contains no `has` and no `loc` leaf."""
    rules = build_location_rules(world, glitch=glitch)
    placement = derive_location_regions(rules)
    helper = RuleHelper(world, glitch=glitch)
    curated = collect_advancement_rules(helper)
    bacap = overlay_packs().get("blazeandcave")
    manifest = _manifest(bacap if (world.options.blazeandcave and bacap) else base_pack())
    # Same construction as build_location_rules, so compile() answers what it answered there.
    compiler = TriggerCompiler(helper, frozenset(world._get_active_locations()))

    findings = []
    for name, rule in sorted(rules.items()):
        if name.startswith("__"):
            continue  # internal event locations: reachability is their whole definition
        gated, regions = rule.gate_summary()
        if gated:
            continue
        data = ADVANCEMENT_LOCATIONS.get(name)
        record = manifest.get(data.game_id) if data else None
        short = name.removeprefix(ADVANCEMENT_PREFIX)
        if name.startswith((ENTITY_KILL_PREFIX, BOSS_KILL_PREFIX)):
            # Mob/boss kills are built by collect_entity_rules, not compiled from criteria: they
            # have no manifest record by design, so absence of one is not the fallback bug.
            bucket = "KILL"
        elif _is_root(name, record):
            bucket = "EXPECTED (root)"
        elif name in FREE_BY_DESIGN:
            bucket = "EXPECTED (free)"
        elif name in REGION_IS_THE_CHECK:
            bucket = "EXPECTED (region edge)"
        else:
            bucket = _provenance(compiler, record, short, curated)
        findings.append({
            "name": name,
            "bucket": bucket,
            "placed": placement.get(name, "?"),
            "regions": sorted(regions) or ["<none — always true>"],
            "triggers": _triggers(record),
            "diagnosis": _diagnose(compiler, record),
            "game_id": data.game_id if data else None,
            "rule": rule.to_dict(),
        })
    return findings


# Ordered worst-first: UNCOMPILED is the actual bug, the rest are listed to be confirmed.
SUSPECT_BUCKETS = ("UNCOMPILED", "CURATED", "KILL", "UNGATED")


def _entry(f: dict, index: int, *, verbose: bool) -> None:
    print(f"  {index:3d}. {f['name']}")
    print(f"       asks only: {', '.join(f['regions'])}   | placed in: {f['placed']}")
    if f["game_id"]:
        print(f"       id: {f['game_id']}")
    if f["diagnosis"]:
        print(f"       criteria: {', '.join(f['diagnosis'])}")
    elif f["bucket"] == "UNCOMPILED":
        print("       no manifest record for this check")
    if verbose:
        print(f"       rule: {json.dumps(f['rule'])}")


def report(label: str, findings: list[dict], *, verbose: bool, quiet: bool = False) -> list[dict]:
    by_bucket = defaultdict(list)
    for f in findings:
        by_bucket[f["bucket"]].append(f)
    suspect = [f for b in SUSPECT_BUCKETS for f in by_bucket[b]]
    if quiet:  # --markdown: stdout is the document, so the per-config detail is suppressed
        return suspect
    print(f"[{label}] {len(findings)} rules gate nothing "
          f"({len(suspect)} suspect, {len(findings) - len(suspect)} expected)")
    for bucket in SUSPECT_BUCKETS:
        entries = by_bucket[bucket]
        if not entries:
            continue
        print(f"\n  --- {bucket} ({len(entries)}) ---")
        for i, f in enumerate(entries, 1):
            _entry(f, i, verbose=verbose)
    expected = (by_bucket["EXPECTED (root)"] + by_bucket["EXPECTED (free)"]
                + by_bucket["EXPECTED (region edge)"])
    if expected:
        print(f"\n  --- EXPECTED ({len(expected)}) --- "
              f"{', '.join(f['name'].removeprefix(ADVANCEMENT_PREFIX) for f in expected)}")
    print()
    return suspect


def main() -> int:
    argv = sys.argv[1:]
    args = set(argv)
    # The report carries em-dashes and advancement titles with non-ASCII characters; the default
    # Windows console encoding mangles both.
    sys.stdout.reconfigure(encoding="utf-8")
    verbose = "--verbose" in args or "-v" in args
    markdown = "--markdown" in args
    out_path = next((argv[i + 1] for i, a in enumerate(argv)
                     if a == "--out" and i + 1 < len(argv)), None)
    if out_path and not os.path.isabs(out_path):
        out_path = os.path.join(REPO_ROOT, out_path)
    graphs = [("strict", False)] + ([("glitch", True)] if "--glitch" in args else [])
    cases = CASES if "--all" in args else {"default (overworld start)": CASES["default (overworld start)"]}

    # name -> (finding, configs it was flagged in). A check only has to be ungated in ONE config to
    # need looking at, so the union is the work list; the config list says where to reproduce it.
    union: dict[str, tuple[dict, list[str]]] = {}
    for label, options in cases.items():
        world = setup_multiworld(WORLD, options=options).worlds[1]
        for graph, glitch in graphs:
            tag = f"{label} | {graph}"
            for f in report(tag, audit(world, glitch=glitch), verbose=verbose, quiet=markdown):
                union.setdefault(f["name"], (f, []))[1].append(tag)

    ordered = sorted(union.items(), key=lambda kv: (SUSPECT_BUCKETS.index(kv[1][0]["bucket"]),
                                                    kv[0]))
    if markdown:
        # Write through a file handle rather than shell redirection: importing AP can print a
        # "Requirement ... press enter to install it" prompt on STDOUT before main() ever runs, and
        # `> file` captures that too — it landed at the top of the generated document.
        if out_path:
            with open(out_path, "w", encoding="utf-8", newline="\n") as fh:
                with redirect_stdout(fh):
                    _markdown(ordered, len(cases), len(graphs))
            print(f"wrote {out_path}")
        else:
            _markdown(ordered, len(cases), len(graphs))
        return 0

    print("=" * 78)
    print(f"UNION across {len(cases)} config(s) x {len(graphs)} graph(s): "
          f"{len(union)} distinct checks to verify")
    print("=" * 78)
    for i, (name, (f, tags)) in enumerate(ordered, 1):
        _entry(f, i, verbose=verbose)
        print(f"       bucket: {f['bucket']}   | flagged in: {'; '.join(tags)}")
    print("\nEach entry is a check whose rule demands no item and no other check. Verify by hand, "
          "then teach logic/triggers.py the trigger or curate it in logic/acquisition.py.")
    return 0


_BUCKET_BLURB = {
    "UNCOMPILED": "No handler read any criterion, and there was no curated rule and no usable "
                  "parent — the rule fell through to `Const(True)`. Fix by adding a trigger handler "
                  "in `logic/triggers.py`.",
    "CURATED": "Hand-written rules in `logic/acquisition.py` that themselves gate nothing. Either "
               "the curation is incomplete or the check really is free.",
    "KILL": "Mob/boss kill checks (`collect_entity_rules`, not criteria). Ungated means nothing "
            "stands between spawn and the kill in the flagged config.",
    "UNGATED": "The criteria compiled, but every node they produced gates nothing. **This is where "
               "the judgement is.** `(handler-free)` on a trigger means a handler exists and "
               "returned something free: right for `acquire` of a genuinely free item (kelp, "
               "bamboo, sand), wrong where the handler punts to a bare region — `slept_in_bed` "
               "returns `access_region(Overworld)` and never asks for the bed. Read the trigger "
               "group headings, not the individual rows.",
}


def _markdown(ordered: list, n_cases: int, n_graphs: int) -> None:
    """A tick-box checklist: bucketed worst-first, then grouped by trigger inside each bucket,
    because the fix belongs to the trigger handler rather than the individual advancement."""
    by_bucket = defaultdict(list)
    for name, (f, tags) in ordered:
        # Ungated in a locks-off config only means the run had no such item to demand, which is
        # expected. Ungated with ALL_LOCKS on is the finding that stands on its own.
        f["hard"] = any(t.startswith("everything on") for t in tags)
        f["configs"] = ", ".join(sorted({t.split(" | ")[0] for t in tags}))
        by_bucket[f["bucket"]].append(f)
    print(f"# Bare-rule backlog — {len(ordered)} checks\n")
    print(f"Generated by `tools/audit_bare_rules.py --all --markdown` over {n_cases} config(s) "
          f"x {n_graphs} graph(s). Every check listed compiles to a rule with no `has` and no "
          f"`loc` leaf: it demands no item and no other check, only (at most) a dimension.\n")
    print("Gating nothing is not by itself a bug — `Kelp me!` compiles perfectly and kelp really "
          "is free. What separates the two is where the rule came from, so the buckets below are "
          "ordered worst-first.\n")
    print("**Read the `All locks on` column first.** A check can be ungated simply because the "
          "run put no AP item behind the thing it needs, which is correct. A **yes** means it was "
          "still ungated with every lock enabled (`challenge_sanity`, `kill_sanity`, "
          "`villager_trust`, `mob_spawn_lock`, `structure_unlock`, `knowledge_gates`, `boss_list` "
          "all on) — nothing in the options can gate it, so it is ungated on its own merits.\n")
    print("| Bucket | Count |")
    print("|---|---|")
    for bucket in SUSPECT_BUCKETS:
        if by_bucket[bucket]:
            print(f"| {bucket} | {len(by_bucket[bucket])} |")
    print()
    for bucket in SUSPECT_BUCKETS:
        entries = by_bucket[bucket]
        if not entries:
            continue
        print(f"## {bucket} — {len(entries)}\n")
        print(f"{_BUCKET_BLURB[bucket]}\n")
        by_trigger = defaultdict(list)
        for f in entries:
            key = ", ".join(f["diagnosis"]) or "(no criteria)"
            by_trigger[key].append(f)
        for trigger, group in sorted(by_trigger.items(), key=lambda kv: (-len(kv[1]), kv[0])):
            hard = sum(1 for f in group if f["hard"])
            print(f"### `{trigger}` — {len(group)} ({hard} with all locks on)\n")
            print("| | Check | Asks only | Placed | All locks on | Id |")
            print("|---|---|---|---|---|---|")
            for f in sorted(group, key=lambda f: (not f["hard"], f["name"])):
                print(f"| [ ] | {f['name'].removeprefix(ADVANCEMENT_PREFIX)} | "
                      f"{', '.join(f['regions'])} | {f['placed']} | "
                      f"{'**yes**' if f['hard'] else 'no'} | `{f['game_id'] or '-'}` |")
            print()


if __name__ == "__main__":
    raise SystemExit(main())
