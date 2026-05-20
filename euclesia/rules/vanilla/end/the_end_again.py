from euclesia.rules.constants import *
from euclesia.rules.helpers import RuleHelper


def the_end_again(helper: RuleHelper) -> dict:
    return {
        A_THE_END_AGAIN: helper.all_of(helper.reached(f"{ADVANCEMENT_PREFIX}Free the End"), helper.entity(E_GHAST))
    }
