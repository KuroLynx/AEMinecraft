from ....constants import *
from ....helpers import RuleHelper


def breeze(helper: RuleHelper) -> dict:
    return {
        E_BREEZE: helper.entity(E_BREEZE)
    }
