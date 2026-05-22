from ....constants import *
from ....helpers import RuleHelper


def iron_golem(helper: RuleHelper) -> dict:
    return {
        E_IRON_GOLEM: helper.entity(E_IRON_GOLEM)
    }
