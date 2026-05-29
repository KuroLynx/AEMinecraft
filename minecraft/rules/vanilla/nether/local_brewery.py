from ...constants import *
from ...helpers import RuleHelper


def local_brewery(helper: RuleHelper) -> dict:
    return {
        A_LOCAL_BREWERY: helper.all_of(helper.reached(f"{ADVANCEMENT_PREFIX}{A_INTO_FIRE}"), helper.knowledge(K_BREWING))
    }
