from worlds.euclesia.rules.constants import *
from worlds.euclesia.rules.helpers import RuleHelper


def wax_on(helper: RuleHelper) -> dict:
    return {
        A_WAX_ON: helper.all_of(
            helper.knowledge("Shear Handling"),
            helper.entity("Bee"),
            helper.can_get_copper(),
        )
    }
