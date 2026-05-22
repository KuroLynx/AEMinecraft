from ...constants import *
from ...helpers import RuleHelper


def wax_off(helper: RuleHelper) -> dict:
    return {
        A_WAX_OFF: helper.all_of(
            helper.reached(f"{ADVANCEMENT_PREFIX}Wax On"),
            helper.knowledge("Axe Handling"),
        ),
    }
