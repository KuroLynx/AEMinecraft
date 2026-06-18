from .. import *  # constants, RuleHelper, mob sets (re-export hub)


def trader_llama(helper: RuleHelper) -> dict:
    return {
        E_TRADER_LLAMA: helper.entity(E_TRADER_LLAMA)
    }
