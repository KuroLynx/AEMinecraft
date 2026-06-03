from ...constants import *
from ...helpers import RuleHelper


def a_seedy_place(helper: RuleHelper) -> dict:
    return {
        A_A_SEEDY_PLACE: helper.any_of(
            helper.knowledge(K_HOE),
            helper.any_village(),
            helper.structure(S_MANSION),
        )
    }
