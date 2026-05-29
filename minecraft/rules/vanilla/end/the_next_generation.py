from ...constants import *
from ...helpers import RuleHelper

def the_next_generation(helper: RuleHelper) -> dict:
    return {
        A_THE_NEXT_GENERATION: helper.reached(f"{ADVANCEMENT_PREFIX}{A_FREE_THE_END}")
    }
