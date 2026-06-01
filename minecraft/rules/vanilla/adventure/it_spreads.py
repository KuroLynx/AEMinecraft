from ...constants import *
from ...helpers import RuleHelper


def it_spreads(helper: RuleHelper) -> dict:
    # Kill a mob next to a Sculk Catalyst — these generate in the Deep Dark biome (deep
    # underground in the Overworld, no structure/item gate).
    return {
        A_IT_SPREADS: helper.can_kill()
    }
