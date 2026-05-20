from euclesia.rules.constants import *
from euclesia.rules.helpers import RuleHelper


def postmortal(helper: RuleHelper) -> dict:
    return {
        A_POSTMORTAL: helper.can_get_totem()
    }
