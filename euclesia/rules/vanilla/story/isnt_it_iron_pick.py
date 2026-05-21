from worlds.euclesia.rules.constants import *
from worlds.euclesia.rules.helpers import RuleHelper


def isnt_it_iron_pick(helper: RuleHelper) -> dict:
    return {
        A_ISNT_IT_IRON_PICK: helper.all_of(
            helper.knowledge(K_PICKAXE),
            helper.any_of(
                helper.reached(f"{ADVANCEMENT_PREFIX}{A_ACQUIRE_HARDWARE}"),  # Craft it yourself
                helper.any_mineshaft(),  # In Mineshaft
                helper.reached(f"{ADVANCEMENT_PREFIX}{A_THE_CITY_AT_THE_END_OF_THE_GAME}"),  # In End City
                helper.reached(f"{ADVANCEMENT_PREFIX}{A_EYE_SPY}"),  # In Stronghold
                helper.any_village(),
                helper.can_trade(False, 3),  # A toolsmith
            ),
        ),
    }
