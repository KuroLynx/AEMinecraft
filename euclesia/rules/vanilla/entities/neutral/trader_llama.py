from ....constants import *
from ....helpers import RuleHelper


def trader_llama(helper: RuleHelper) -> dict:
    return {
        E_TRADER_LLAMA: helper.entity(E_TRADER_LLAMA)
    }
