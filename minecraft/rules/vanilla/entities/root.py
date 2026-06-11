from .. import *  # constants, RuleHelper, mob sets (re-export hub)
from .passive.root import get_passive_rules
from .neutral.root import get_neutral_rules
from .hostile.root import get_hostile_rules
from .boss.root import get_boss_rules


def get_entities_rules(helper: RuleHelper) -> dict:
    return (
        get_passive_rules(helper) |
        get_neutral_rules(helper) |
        get_hostile_rules(helper) |
        get_boss_rules(helper)
    )
