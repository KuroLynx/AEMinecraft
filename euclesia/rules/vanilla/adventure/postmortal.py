from worlds.euclesia.rules.constants import *
from worlds.euclesia.rules.helpers import RuleHelper


def postmortal(helper: RuleHelper) -> dict:
    return {
        A_POSTMORTAL: helper.can_get_totem()
    }
