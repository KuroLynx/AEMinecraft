from ...constants import *
from ...helpers import RuleHelper


def cover_me_with_diamonds(helper: RuleHelper) -> dict:
    return {
        A_COVER_ME_WITH_DIAMONDS: helper.all_of(
            helper.knowledge(K_ARMOR),  # Must always have that
            helper.any_of(
                helper.reached(f"{ADVANCEMENT_PREFIX}{A_DIAMONDS}"),  # Craft it yourself
                helper.can_trade_villager(4),  # Armorer (taiga) sells full diamond armor at lvl 4
                helper.reached(f"{ADVANCEMENT_PREFIX}{A_THOSE_WERE_THE_DAYS}"),  # In Bastion Remnant
                helper.reached(f"{ADVANCEMENT_PREFIX}{A_THE_CITY_AT_THE_END_OF_THE_GAME}"),  # In End City
                helper.structure(S_MANSION),
                helper.reached(f"{ADVANCEMENT_PREFIX}{A_UNDER_LOCK_AND_KEY}"),  # In Trial Chambers (Vault)
                helper.reached(f"{ADVANCEMENT_PREFIX}{A_REVAULTING}"),  # In Trial Chambers (Ominous Vault)
                helper.structure(S_ANCIENT_CITY),
            ),
        ),
    }
