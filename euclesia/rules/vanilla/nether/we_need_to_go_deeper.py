from worlds.euclesia.rules.constants import *
from worlds.euclesia.rules.helpers import RuleHelper

def we_need_to_go_deeper(helper: RuleHelper) -> dict:
    return {
        A_WE_NEED_TO_GO_DEEPER: helper.all_of()
    }
