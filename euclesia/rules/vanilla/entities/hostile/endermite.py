from ....constants import *
from ....helpers import RuleHelper


def endermite(helper: RuleHelper) -> dict:
    return {
        E_ENDERMITE: helper.entity(E_ENDERMITE)
    }
