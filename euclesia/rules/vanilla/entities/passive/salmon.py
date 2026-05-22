from ....constants import *
from ....helpers import RuleHelper


def salmon(helper: RuleHelper) -> dict:
    return {
        E_SALMON: helper.entity(E_SALMON)
    }
