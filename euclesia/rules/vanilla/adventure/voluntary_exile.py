from ...constants import *
from ...helpers import RuleHelper


def voluntary_exile(helper: RuleHelper) -> dict:
    return {
        A_VOLUNTARY_EXILE: helper.entity(E_PILLAGER)
    }
