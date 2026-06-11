from .. import *  # constants, RuleHelper, mob sets (re-export hub)


def wax_on(helper: RuleHelper) -> dict:
    return {
        # Wax On = apply honeycomb to a copper block. Needs honeycomb (beehive shear or Trial
        # Chambers chest) plus a copper block.
        A_WAX_ON: helper.all_of(
            helper.can_get_honeycomb(),
            helper.can_get_copper(),
        )
    }
