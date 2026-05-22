from ....constants import *
from ....helpers import RuleHelper


def zombified_piglin(helper: RuleHelper) -> dict:
    return {
        E_ZOMBIFIED_PIGLIN: helper.entity(E_ZOMBIFIED_PIGLIN)
    }
