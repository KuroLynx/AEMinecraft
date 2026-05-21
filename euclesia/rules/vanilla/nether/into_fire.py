from worlds.euclesia.rules.constants import *
from worlds.euclesia.rules.helpers import RuleHelper

def into_fire(helper: RuleHelper) -> dict:
    return {
        A_INTO_FIRE: helper.all_of(
            helper.reached(f"{ADVANCEMENT_PREFIX}{A_A_TERRIBLE_FORTRESS}"),
            helper.entity("Blaze"),
        ),
    }
