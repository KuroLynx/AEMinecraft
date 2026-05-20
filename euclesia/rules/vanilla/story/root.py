from euclesia.rules.helpers import RuleHelper
from euclesia.rules.vanilla.story.acquire_hardware import acquire_hardware
from euclesia.rules.vanilla.story.cover_me_with_diamonds import cover_me_with_diamonds
from euclesia.rules.vanilla.story.diamonds import diamonds
from euclesia.rules.vanilla.story.enchanter import enchanter
from euclesia.rules.vanilla.story.enter_end_portal import enter_end_portal
from euclesia.rules.vanilla.story.eye_spy import eye_spy
from euclesia.rules.vanilla.story.getting_an_upgrade import getting_an_upgrade
from euclesia.rules.vanilla.story.hot_stuff import hot_stuff
from euclesia.rules.vanilla.story.ice_bucket_challenge import ice_bucket_challenge
from euclesia.rules.vanilla.story.isnt_it_iron_pick import isnt_it_iron_pick
from euclesia.rules.vanilla.story.not_today_thank_you import not_today_thank_you
from euclesia.rules.vanilla.story.stone_age import stone_age
from euclesia.rules.vanilla.story.suit_up import suit_up
from euclesia.rules.vanilla.story.zombie_doctor import zombie_doctor


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