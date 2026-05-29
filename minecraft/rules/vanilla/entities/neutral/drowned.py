from ....constants import *
from ....helpers import RuleHelper


def drowned(helper: RuleHelper) -> dict:
    return {
        E_DROWNED: helper.entity(E_DROWNED)
    }
