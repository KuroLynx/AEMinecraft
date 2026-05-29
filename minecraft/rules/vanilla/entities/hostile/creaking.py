from ....constants import *
from ....helpers import RuleHelper


def creaking(helper: RuleHelper) -> dict:
    return {
        E_CREAKING: helper.entity(E_CREAKING)
    }
