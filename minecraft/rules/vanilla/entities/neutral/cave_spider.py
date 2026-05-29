from ....constants import *
from ....helpers import RuleHelper


def cave_spider(helper: RuleHelper) -> dict:
    return {
        E_CAVE_SPIDER: helper.entity(E_CAVE_SPIDER)
    }
