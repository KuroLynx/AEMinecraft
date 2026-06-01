from ...constants import *
from ...helpers import RuleHelper


def sweet_dreams(helper: RuleHelper) -> dict:
    return {
        A_SWEET_DREAMS: helper.can_get_bed()
    }
