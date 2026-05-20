from euclesia.rules.constants import *
from euclesia.rules.helpers import RuleHelper


def bullseye(helper: RuleHelper) -> dict:
    return {
        A_BULLSEYE: helper.all_of(
            helper.can_get_redstone(),  # Target Block needs Redstone
            helper.any_of(
                helper.all_of(helper.knowledge("Sharpshooter"), helper.can_get_arrow()),  # bow/crossbow + arrow
                helper.can_get_snowball(),
                helper.can_get_egg(),
                helper.entity("Breeze"),  # Wind Charge
            ),
        )
    }
