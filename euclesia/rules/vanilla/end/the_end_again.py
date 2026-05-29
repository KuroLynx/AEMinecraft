from ...constants import *
from ...helpers import RuleHelper


def the_end_again(helper: RuleHelper) -> dict:
    return {
        A_THE_END_AGAIN: helper.all_of(helper.reached(f"{ADVANCEMENT_PREFIX}{A_FREE_THE_END}"), helper.entity(E_GHAST))
    }
