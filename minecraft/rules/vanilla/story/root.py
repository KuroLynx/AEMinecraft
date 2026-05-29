from ...helpers import RuleHelper
from .stone_age import stone_age
from .getting_an_upgrade import getting_an_upgrade
from .acquire_hardware import  acquire_hardware
from .suit_up import suit_up
from .hot_stuff import hot_stuff
from .isnt_it_iron_pick import isnt_it_iron_pick
from .not_today_thank_you import not_today_thank_you
from .diamonds import diamonds
from .ice_bucket_challenge import ice_bucket_challenge
from .cover_me_with_diamonds import cover_me_with_diamonds
from .enchanter import enchanter
from .zombie_doctor import zombie_doctor
from .eye_spy import eye_spy
from .enter_end_portal import enter_end_portal

def get_story_rules(helper: RuleHelper) -> dict:
    return (
        stone_age(helper) |
        getting_an_upgrade(helper) |
        acquire_hardware(helper) |
        suit_up(helper) |
        hot_stuff(helper) |
        isnt_it_iron_pick(helper) |
        not_today_thank_you(helper) |
        diamonds(helper) |
        ice_bucket_challenge(helper) |
        cover_me_with_diamonds(helper) |
        enchanter(helper) |
        zombie_doctor(helper) |
        eye_spy(helper) |
        enter_end_portal(helper)
    )