from ...constants import *
from ...helpers import RuleHelper


def respecting_the_remnants(helper: RuleHelper) -> dict:
    return {
        A_RESPECTING_THE_REMNANTS: helper.all_of(
            helper.has_brush(),
            helper.any_of(
                helper.structure(S_TRAIL_RUINS),  # Burn, Danger, Friend, Heart, Heartbreak, Howl, Sheaf
                helper.structure(S_OCEAN_RUIN_WARM),  # Angler, Shelter, Snort
                helper.structure(S_OCEAN_RUIN_COLD),  # Blade, Explorer, Mourner, Plenty
                helper.structure(S_DESERT_PYRAMID),  # Archer, Miner, Prize, Skull
                helper.structure("Desert Well"),  # Arms Up, Brewer
            ),
        )
    }
