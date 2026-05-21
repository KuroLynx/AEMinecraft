from worlds.euclesia.rules.constants import *
from worlds.euclesia.rules.helpers import RuleHelper

def the_next_generation(helper: RuleHelper) -> dict:
    return {
        A_THE_NEXT_GENERATION: helper.reached(f"{ADVANCEMENT_PREFIX}Free the End")
    }
