from ...constants import *
from ...helpers import RuleHelper


def zombie_doctor(helper: RuleHelper) -> dict:
    return {
        A_ZOMBIE_DOCTOR: helper.all_of(
            helper.entity(E_ZOMBIE_VILLAGER),
            # Golden Apple
            helper.any_of(
                helper.can_get_gold(),
                helper.any_mineshaft(),
                helper.reached(f"{ADVANCEMENT_PREFIX}{A_THOSE_WERE_THE_DAYS}"),  # In Bastion Remnant
                helper.structure(S_DESERT_PYRAMID),
                helper.structure(S_IGLOO),
                helper.any_portal(True),
                helper.structure(S_DUNGEON),
                helper.reached(f"{ADVANCEMENT_PREFIX}{A_EYE_SPY}"),  # In Stronghold
                helper.structure(S_OCEAN_RUIN_COLD),
                helper.structure(S_OCEAN_RUIN_WARM),
                helper.structure(S_MANSION),
            ),
            # Weakness potion
            helper.any_of(
                # Brewing
                helper.all_of(
                    helper.reached(f"{ADVANCEMENT_PREFIX}Local Brewery"),
                    helper.has_any_entities("Cave Spider", "Spider"),
                ),
                # Finding
                helper.all_of(helper.knowledge("Brewing"), helper.structure(S_IGLOO)),
                helper.reached(f"{ADVANCEMENT_PREFIX}{A_TRIAL_EDITION}"),
                helper.entity(E_WITCH),
            ),
        ),
    }
