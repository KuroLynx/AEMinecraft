from ...constants import *
from ...helpers import RuleHelper


def wax_on(helper: RuleHelper) -> dict:
    return {
        A_WAX_ON: helper.all_of(
            helper.knowledge(K_SHEAR),
            helper.entity(E_BEE),
            helper.can_get_copper(),
        )
    }
