from euclesia.rules.constants import *
from euclesia.rules.helpers import RuleHelper


def withering_heights(helper: RuleHelper) -> dict:
    return {
        A_WITHERING_HEIGHTS: helper.all_of(helper.reached(f"{ADVANCEMENT_PREFIX}Spooky Scary Skeleton"), helper.entity("Wither"))
    }
