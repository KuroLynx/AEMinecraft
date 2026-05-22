from ....constants import *
from ....helpers import RuleHelper


def witch(helper: RuleHelper) -> dict:
    return {
        E_WITCH: helper.entity(E_WITCH)
    }
