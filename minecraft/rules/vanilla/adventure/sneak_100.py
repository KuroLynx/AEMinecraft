from ...constants import *
from ...helpers import RuleHelper


def sneak_100(helper: RuleHelper) -> dict:
    return {
        A_SNEAK_100: helper.all_of(
            helper.knowledge(K_PICKAXE)
        )
    }
