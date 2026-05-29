from ....constants import *
from ....helpers import RuleHelper


def enderman(helper: RuleHelper) -> dict:
    return {
        E_ENDERMAN: helper.entity(E_ENDERMAN)
    }
