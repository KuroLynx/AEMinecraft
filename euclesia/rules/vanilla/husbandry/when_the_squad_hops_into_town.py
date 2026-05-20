from euclesia.rules.constants import *
from euclesia.rules.helpers import RuleHelper


def when_the_squad_hops_into_town(helper: RuleHelper) -> dict:
    return {
        A_WHEN_THE_SQUAD_HOPS_INTO_TOWN: helper.entity("Frog")
    }
