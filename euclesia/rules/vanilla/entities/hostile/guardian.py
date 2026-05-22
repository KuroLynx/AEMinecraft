from ....constants import *
from ....helpers import RuleHelper


def guardian(helper: RuleHelper) -> dict:
    return {
        E_GUARDIAN: helper.entity(E_GUARDIAN)
    }
