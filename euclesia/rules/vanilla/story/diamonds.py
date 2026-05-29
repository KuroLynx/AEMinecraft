from ...constants import *
from ...helpers import RuleHelper


def diamonds(helper: RuleHelper) -> dict:
    return {
        A_DIAMONDS: helper.all_of(
            helper.material(MAT_DIAMOND),
            helper.any_of(
                helper.knowledge(K_PICKAXE),  # Mine it yourself
                helper.any_mineshaft(),  # In Mineshaft
                helper.reached(f"{ADVANCEMENT_PREFIX}{A_THOSE_WERE_THE_DAYS}"),  # In Bastion Remnant
                helper.structure(S_DESERT_PYRAMID),  # In Desert Pyramid (Chest)
                helper.structure(S_JUNGLE_PYRAMID),
                helper.structure(S_BURIED_TREASURE),
                helper.any_shipwreck(),
                helper.reached(f"{ADVANCEMENT_PREFIX}{A_A_TERRIBLE_FORTRESS}"),  # In Nether Fortress
                helper.reached(f"{ADVANCEMENT_PREFIX}{A_THE_CITY_AT_THE_END_OF_THE_GAME}"),  # In End City
                helper.reached(f"{ADVANCEMENT_PREFIX}{A_EYE_SPY}"),  # In Stronghold

                helper.reached(f"{ADVANCEMENT_PREFIX}{A_TRIAL_EDITION}"),  # In Trial Chambers (Chest & Pots)
                helper.reached(f"{ADVANCEMENT_PREFIX}{A_UNDER_LOCK_AND_KEY}"),  # In Trial Chambers (Vault)
                helper.reached(f"{ADVANCEMENT_PREFIX}{A_REVAULTING}"),  # In Trial Chambers (Ominous Vault)

                helper.any_village(),  # In Village

                helper.all_of(  # In Desert Pyramid (Archeology)
                    helper.structure(S_DESERT_PYRAMID),
                    helper.has_brush(),
                ),
            ),
        ),
    }
