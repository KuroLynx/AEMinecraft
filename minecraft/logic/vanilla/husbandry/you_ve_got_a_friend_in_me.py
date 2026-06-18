from .. import *  # constants, RuleHelper, mob sets (re-export hub)

def you_ve_got_a_friend_in_me(helper: RuleHelper) -> dict:
    return {
        A_YOU_VE_GOT_A_FRIEND_IN_ME: helper.entity(E_ALLAY)
    }
