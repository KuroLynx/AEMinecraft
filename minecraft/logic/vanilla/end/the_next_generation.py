from .. import *  # constants, RuleHelper, mob sets (re-export hub)

def the_next_generation(helper: RuleHelper) -> dict:
    return {
        A_THE_NEXT_GENERATION: helper.reached(f"{ADVANCEMENT_PREFIX}{A_FREE_THE_END}")
    }
