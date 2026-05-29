from ....constants import *
from ....helpers import RuleHelper


def dolphin(helper: RuleHelper) -> dict:
    return {
        E_DOLPHIN: helper.entity(E_DOLPHIN)
    }
