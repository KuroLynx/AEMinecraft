from ...constants import *
from ...helpers import RuleHelper

def not_today_thank_you(helper: RuleHelper) -> dict:
    return {
        A_NOT_TODAY: helper.all_of(
            helper.knowledge(K_SHIELD),
            helper.any_of(
                helper.reached(f"{ADVANCEMENT_PREFIX}{A_ACQUIRE_HARDWARE}"),  # Craft it yourself
                helper.reached(f"{ADVANCEMENT_PREFIX}{A_UNDER_LOCK_AND_KEY}"),  # In Trial Chambers Vault
                helper.can_trade_villager(3),
            ),
        ),
    }
