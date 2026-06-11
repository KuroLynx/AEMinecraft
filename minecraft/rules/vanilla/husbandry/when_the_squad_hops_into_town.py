from .. import *  # constants, RuleHelper, mob sets (re-export hub)


def when_the_squad_hops_into_town(helper: RuleHelper) -> dict:
    return {
        A_WHEN_THE_SQUAD_HOPS_INTO_TOWN: helper.entity(E_FROG)
    }
