from ....constants import *
from ....helpers import RuleHelper


def creeper(helper: RuleHelper) -> dict:
    return {
        E_CREEPER: helper.entity(E_CREEPER)
    }
