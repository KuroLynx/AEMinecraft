from ....constants import *
from ....helpers import RuleHelper


def elder_guardian(helper: RuleHelper) -> dict:
    return {
        E_ELDER_GUARDIAN: helper.entity(E_ELDER_GUARDIAN)
    }
