from worlds.euclesia.rules.constants import *
from worlds.euclesia.rules.helpers import RuleHelper


def voluntary_exile(helper: RuleHelper) -> dict:
    return {
        A_VOLUNTARY_EXILE: helper.entity("Pillager")
    }
