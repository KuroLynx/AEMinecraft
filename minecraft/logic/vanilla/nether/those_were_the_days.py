from .. import *  # constants, RuleHelper, mob sets (re-export hub)

def those_were_the_days(helper: RuleHelper) -> dict:
    return {
        A_THOSE_WERE_THE_DAYS: helper.structure("Bastion Remnant")
    }
