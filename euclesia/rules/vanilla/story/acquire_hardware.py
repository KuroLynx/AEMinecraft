from ...constants import *
from ...helpers import RuleHelper


def acquire_hardware(helper: RuleHelper) -> dict:
    return {
        A_ACQUIRE_HARDWARE: helper.all_of(
            helper.material(MAT_IRON),  # You always need to have unlocked iron handling
            helper.any_of(
                helper.reached(f"{ADVANCEMENT_PREFIX}{A_STONE_AGE}"),  # You can mine iron ore
                helper.any_village(),  # Find it in any village chests
                helper.any_mineshaft(),  # Find it in any mineshaft chests
                helper.structure(S_BURIED_TREASURE),
                helper.structure(S_SHIPWRECK),
                helper.structure(S_DESERT_PYRAMID),
                helper.structure(S_JUNGLE_PYRAMID),
                helper.structure(S_MANSION),
                helper.reached(f"{ADVANCEMENT_PREFIX}{A_TRIAL_EDITION}"),

                helper.reached(f"{ADVANCEMENT_PREFIX}{A_EYE_SPY}"),  # In Stronghold
                helper.reached(f"{ADVANCEMENT_PREFIX}{A_A_TERRIBLE_FORTRESS}"),  # In Nether Fortress
                helper.reached(f"{ADVANCEMENT_PREFIX}{A_THOSE_WERE_THE_DAYS}"),  # In Bastion Remnant
                helper.reached(f"{ADVANCEMENT_PREFIX}{A_THE_CITY_AT_THE_END_OF_THE_GAME}"),  # In End City

                helper.can_barter(),  # Piglin trade gives nuggets

                helper.any_portal(True),
                helper.has_any_entities(E_HUSK, E_IRON_GOLEM, E_ZOMBIE, E_ZOMBIE_VILLAGER),
            ),
        ),
    }
