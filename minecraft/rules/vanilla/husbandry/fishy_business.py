from ...constants import *
from ...helpers import RuleHelper


def fishy_business(helper: RuleHelper) -> dict:
    return {
        A_FISHY_BUSINESS: helper.knowledge(K_FISHING)
    }
