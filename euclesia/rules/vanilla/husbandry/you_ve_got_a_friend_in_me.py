from worlds.euclesia.rules.constants import *
from worlds.euclesia.rules.helpers import RuleHelper

def you_ve_got_a_friend_in_me(helper: RuleHelper) -> dict:
    return {
        A_YOU_VE_GOT_A_FRIEND_IN_ME: helper.entity("Allay")
    }
