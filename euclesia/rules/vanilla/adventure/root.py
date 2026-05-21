from worlds.euclesia.rules.helpers import RuleHelper
from .a_throwaway_joke import a_throwaway_joke
from .adventuring_time import adventuring_time
from .arbalistic import arbalistic
from .blowback import blowback
from .bullseye import bullseye
from .careful_restoration import careful_restoration
from .caves_and_cliffs import caves_and_cliffs
from .country_lode_take_me_home import country_lode_take_me_home
from .crafters_crafting_crafters import crafters_crafting_crafters
from .crafting_a_new_look import crafting_a_new_look
from .heart_transplanter import heart_transplanter
from .hero_of_the_village import hero_of_the_village
from .hired_help import hired_help
from .is_it_a_balloon import is_it_a_balloon
from .is_it_a_bird import is_it_a_bird
from .is_it_a_plane import is_it_a_plane
from .isnt_it_scute import isnt_it_scute
from .it_spreads import it_spreads
from .light_as_a_rabbit import light_as_a_rabbit
from .lighten_up import lighten_up
from .mob_kabob import mob_kabob
from .monster_hunter import monster_hunter
from .monsters_hunted import monsters_hunted
from .ol_betsy import ol_betsy
from .over_overkill import over_overkill
from .postmortal import postmortal
from .respecting_the_remnants import respecting_the_remnants
from .revaulting import revaulting
from .smithing_with_style import smithing_with_style
from .sniper_duel import sniper_duel
from .sound_of_music import sound_of_music
from .star_trader import star_trader
from .sticky_situation import sticky_situation
from .surge_protector import surge_protector
from .sweet_dreams import sweet_dreams
from .take_aim import take_aim
from .the_power_of_books import the_power_of_books
from .trial_edition import trial_edition
from .two_birds_one_arrow import two_birds_one_arrow
from .under_lock_and_key import under_lock_and_key
from .very_very_frightening import very_very_frightening
from .voluntary_exile import voluntary_exile
from .what_a_deal import what_a_deal
from .who_is_the_pillager_now import who_is_the_pillager_now
from .sneak_100 import sneak_100
from .who_needs_rockets import who_needs_rockets


def get_adventure_rules(helper: RuleHelper) -> dict:
    return (
            a_throwaway_joke(helper) |
            adventuring_time(helper) |
            arbalistic(helper) |
            blowback(helper) |
            bullseye(helper) |
            careful_restoration(helper) |
            caves_and_cliffs(helper) |
            country_lode_take_me_home(helper) |
            crafters_crafting_crafters(helper) |
            crafting_a_new_look(helper) |
            heart_transplanter(helper) |
            hero_of_the_village(helper) |
            hired_help(helper) |
            is_it_a_balloon(helper) |
            is_it_a_bird(helper) |
            is_it_a_plane(helper) |
            isnt_it_scute(helper) |
            it_spreads(helper) |
            light_as_a_rabbit(helper) |
            lighten_up(helper) |
            mob_kabob(helper) |
            monster_hunter(helper) |
            monsters_hunted(helper) |
            ol_betsy(helper) |
            over_overkill(helper) |
            postmortal(helper) |
            respecting_the_remnants(helper) |
            revaulting(helper) |
            smithing_with_style(helper) |
            sneak_100(helper) |
            sniper_duel(helper) |
            sound_of_music(helper) |
            star_trader(helper) |
            sticky_situation(helper) |
            surge_protector(helper) |
            sweet_dreams(helper) |
            take_aim(helper) |
            the_power_of_books(helper) |
            trial_edition(helper) |
            two_birds_one_arrow(helper) |
            under_lock_and_key(helper) |
            very_very_frightening(helper) |
            voluntary_exile(helper) |
            what_a_deal(helper) |
            who_is_the_pillager_now(helper) |
            who_needs_rockets(helper)
    )
