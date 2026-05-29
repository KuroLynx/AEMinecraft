from ....constants import *
from ....helpers import RuleHelper


def bogged(helper: RuleHelper) -> dict:
    return {
        E_BOGGED: helper.entity(E_BOGGED)
    }
