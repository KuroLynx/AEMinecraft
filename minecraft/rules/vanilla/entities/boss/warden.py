from ....constants import *
from ....helpers import RuleHelper


def warden(helper: RuleHelper) -> dict:
    return {
        E_WARDEN: helper.all_of(
            helper.entity(E_WARDEN),
            helper.can_kill(),
        )
    }
