"""Auto-discovering rule collector.

Replaces the hand-maintained per-tab ``root.py`` aggregators (which imported and OR-merged every
rule file by name) with a single walk over the rule packages. Each leaf rule module defines exactly
one builder named after the module — verified 207/207 — that takes the :class:`RuleHelper` and
returns a ``{name: Rule}`` dict. Dropping a new rule file into a tab package is now enough to wire
it; there is no longer a list of imports to maintain.

Two collections are kept because their keys map to different AP locations:

* **advancement rules** — keyed by advancement display name → ``Advancement: <name>`` locations
* **entity rules**      — keyed by mob name                 → ``Kill Entity:`` / ``Kill Boss:`` locations

The two tab *root* advancements that carry a real rule (Husbandry, Adventure) are injected here for
now. Once the Fabric mod dumps the advancement manifest, tab roots come from that data instead and
these injections go away (see the architecture plan, Milestone 3).
"""
import importlib
import pkgutil
from types import ModuleType

from ..data import MOBS_ALL
from .constants import A_ADVENTURE, A_HUSBANDRY
from .helpers import RuleHelper
from .vanilla import adventure, end, entities, husbandry, nether, story

# Leaf modules named like these are aggregators / package markers, not rule builders.
_SKIP = {"root", "__init__"}

# Advancement tab packages — one builder per advancement.
_ADVANCEMENT_TABS: tuple[ModuleType, ...] = (story, nether, end, adventure, husbandry)


def _collect(helper: RuleHelper, package: ModuleType) -> dict:
    """Import every leaf module under ``package`` (recursively) and merge the dict each module's
    same-named builder returns. Mirrors the old per-tab ``root.py`` hand-merges, generically."""
    merged: dict = {}
    for info in pkgutil.walk_packages(package.__path__, package.__name__ + "."):
        if info.ispkg:
            continue
        basename = info.name.rsplit(".", 1)[-1]
        if basename in _SKIP:
            continue
        module = importlib.import_module(info.name)
        merged.update(getattr(module, basename)(helper))
    return merged


def collect_advancement_rules(helper: RuleHelper) -> dict:
    """All advancement rules across every story/nether/end/adventure/husbandry tab, plus the two
    tab-root advancements that carry an explicit rule."""
    merged: dict = {}
    for tab in _ADVANCEMENT_TABS:
        merged.update(_collect(helper, tab))
    # Tab roots with real rules (the rest fall back to Const(True) in set_rules, as before).
    merged[A_HUSBANDRY] = helper.can_get_food()
    merged[A_ADVENTURE] = helper.has_any_entities(*MOBS_ALL.keys())
    return merged


def collect_entity_rules(helper: RuleHelper) -> dict:
    """All entity (mob/boss) kill rules across the passive/neutral/hostile/boss packages."""
    return _collect(helper, entities)
