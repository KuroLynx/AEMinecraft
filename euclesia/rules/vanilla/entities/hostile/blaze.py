from ....constants import *
from ....helpers import RuleHelper


def blaze(helper: RuleHelper) -> dict:
    return {
        E_BLAZE: helper.entity(E_BLAZE)
    }
