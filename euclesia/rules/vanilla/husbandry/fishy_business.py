from euclesia.rules.constants import *
from euclesia.rules.helpers import RuleHelper


def fishy_business(helper: RuleHelper) -> dict:
    return {
        A_FISHY_BUSINESS: helper.knowledge(K_FISHING)
    }
