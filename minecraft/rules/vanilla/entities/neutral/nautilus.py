from ....constants import *
from ....helpers import RuleHelper


def nautilus(helper: RuleHelper) -> dict:
    return {
        E_NAUTILUS: helper.entity(E_NAUTILUS)
    }
