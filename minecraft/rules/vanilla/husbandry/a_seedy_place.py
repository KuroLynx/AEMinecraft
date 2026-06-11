from .. import *  # constants, RuleHelper, mob sets (re-export hub)


def a_seedy_place(helper: RuleHelper) -> dict:
    return {
        A_A_SEEDY_PLACE: helper.any_of(
            helper.knowledge(K_HOE),
            helper.any_village(),
            helper.structure(S_MANSION),
        )
    }
