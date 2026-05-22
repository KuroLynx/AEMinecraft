from ...constants import *
from ...helpers import RuleHelper

def ol_betsy(helper: RuleHelper) -> dict:
    return {
        A_OL_BETSY: helper.all_of(
            helper.knowledge("Sharpshooter"),
            # Crossbow
            helper.any_of(
                helper.all_of(helper.reached(f"{ADVANCEMENT_PREFIX}{A_ACQUIRE_HARDWARE}"), helper.can_get_string()),
                # craft Crossbow (Iron + Tripwire + String)
                helper.reached(f"{ADVANCEMENT_PREFIX}{A_THOSE_WERE_THE_DAYS}"),  # Bastion Remnant chest
                helper.structure(S_PILLAGER_OUTPOST),  # Pillager Outpost chest
                helper.reached(f"{ADVANCEMENT_PREFIX}{A_UNDER_LOCK_AND_KEY}"),  # Trial Chambers Vault
                helper.reached(f"{ADVANCEMENT_PREFIX}{A_REVAULTING}"),  # Trial Chambers Ominous Vault
            ),
            # Arrow
            helper.can_get_arrow(),
        )
    }
