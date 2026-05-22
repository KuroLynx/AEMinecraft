from ...constants import *
from ...helpers import RuleHelper


def lighten_up(helper: RuleHelper) -> dict:
    return {
        A_LIGHTEN_UP: helper.all_of(
            helper.knowledge(K_AXE),
            helper.any_of(
                helper.all_of(
                    helper.can_get_copper(),  # craft Copper Bulb
                    helper.can_get_redstone(),  # Redstone
                    helper.reached(f"{ADVANCEMENT_PREFIX}{A_INTO_FIRE}"),  # Blaze Rod
                ),
                helper.reached(f"{ADVANCEMENT_PREFIX}{A_TRIAL_EDITION}"),  # found in Trial Chambers
            ),
        )
    }
