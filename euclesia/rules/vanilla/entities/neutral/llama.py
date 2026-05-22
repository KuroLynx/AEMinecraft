from ....constants import *
from ....helpers import RuleHelper


def llama(helper: RuleHelper) -> dict:
    return {
        E_LLAMA: helper.entity(E_LLAMA)
    }
