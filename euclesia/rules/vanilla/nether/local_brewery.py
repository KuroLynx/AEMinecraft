from euclesia.rules.constants import *
from euclesia.rules.helpers import RuleHelper


def local_brewery(helper: RuleHelper) -> dict:
    return {
        A_LOCAL_BREWERY: helper.all_of(helper.reached(f"{ADVANCEMENT_PREFIX}Into Fire"), helper.knowledge("Brewing"))
    }
