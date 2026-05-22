from ...constants import *
from ...helpers import RuleHelper


def who_needs_rockets(helper: RuleHelper) -> dict:
    return {
        A_WHO_NEEDS_ROCKETS: helper.any_of(
            helper.entity("Breeze"),  # Breeze Rod → craft Wind Charge
            helper.reached(f"{ADVANCEMENT_PREFIX}{A_UNDER_LOCK_AND_KEY}"),  # Wind Charge in Common Vault
            helper.reached(f"{ADVANCEMENT_PREFIX}{A_REVAULTING}"),  # Wind Charge in Ominous Common Vault
        )
    }
