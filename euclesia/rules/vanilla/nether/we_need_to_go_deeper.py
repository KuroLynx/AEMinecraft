from euclesia.rules.constants import *
from euclesia.rules.helpers import RuleHelper


def we_need_to_go_deeper(helper: RuleHelper) -> dict:
    return {
        A_WE_NEED_TO_GO_DEEPER: helper.all_of()
    }
