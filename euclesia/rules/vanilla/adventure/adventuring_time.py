from ...constants import *
from ...helpers import RuleHelper


def adventuring_time(helper: RuleHelper) -> dict:
    return {
        A_ADVENTURING_TIME: helper.all_of()
    }
