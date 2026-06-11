from .. import *  # constants, RuleHelper, mob sets (re-export hub)


def llama(helper: RuleHelper) -> dict:
    return {
        E_LLAMA: helper.entity(E_LLAMA)
    }
