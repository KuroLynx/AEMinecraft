from .. import *  # constants, RuleHelper, mob sets (re-export hub)


def magma_cube(helper: RuleHelper) -> dict:
    return {
        E_MAGMA_CUBE: helper.entity(E_MAGMA_CUBE)
    }
