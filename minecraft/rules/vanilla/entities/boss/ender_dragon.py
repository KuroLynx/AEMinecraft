from ....constants import *
from ....helpers import RuleHelper


def ender_dragon(helper: RuleHelper) -> dict:
    return {
        E_ENDER_DRAGON: helper.all_of(
            helper.entity(E_ENDER_DRAGON),
            helper.any_of(
                helper.can_kill(),
                helper.can_get_bed(),  # bed-bombing strategy
            ),
        )
    }
