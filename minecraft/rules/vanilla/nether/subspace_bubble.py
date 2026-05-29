from ...constants import *
from ...helpers import RuleHelper

def subspace_bubble(helper: RuleHelper) -> dict:
    return {
        A_SUBSPACE_BUBBLE: helper.all_of()
    }
