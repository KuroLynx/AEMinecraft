from worlds.euclesia.rules.constants import *
from worlds.euclesia.rules.helpers import RuleHelper

def suit_up(helper: RuleHelper) -> dict:
    return {
        A_SUIT_UP: helper.all_of(
            helper.knowledge(K_ARMOR),  # Always needing armor handling
            helper.any_of(
                helper.reached(f"{ADVANCEMENT_PREFIX}{A_ACQUIRE_HARDWARE}"),  # Craft it yourself
                helper.can_trade(False),  # Tradeable at novice level from armorer / in any_village
                helper.reached(f"{ADVANCEMENT_PREFIX}{A_THE_CITY_AT_THE_END_OF_THE_GAME}"),  # In End City
                helper.reached(f"{ADVANCEMENT_PREFIX}{A_EYE_SPY}"),  # In Stronghold
                helper.reached(f"{ADVANCEMENT_PREFIX}{A_UNDER_LOCK_AND_KEY}"),  # In Trial Chambers Vault
                helper.structure(S_ANCIENT_CITY),
                helper.can_barter(),  # Iron Boots
            ),
        ),
    }
