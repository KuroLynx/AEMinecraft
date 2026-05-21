from worlds.euclesia.rules.constants import *
from worlds.euclesia.rules.helpers import RuleHelper


def adventuring_time(helper: RuleHelper) -> dict:
    return {
        A_ADVENTURING_TIME: helper.all_of()
    }
