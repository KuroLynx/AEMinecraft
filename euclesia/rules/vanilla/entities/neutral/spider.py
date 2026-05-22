from ....constants import *
from ....helpers import RuleHelper


def spider(helper: RuleHelper) -> dict:
    return {
        E_SPIDER: helper.entity(E_SPIDER)
    }
