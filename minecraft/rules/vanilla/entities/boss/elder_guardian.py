from ....constants import *
from ....helpers import RuleHelper


def elder_guardian(helper: RuleHelper) -> dict:
    return {
        E_ELDER_GUARDIAN: helper.all_of(
            helper.entity(E_ELDER_GUARDIAN),
            helper.can_kill(),
            helper.can_breath_underwater(),  # survive the fight underwater
        )
    }
