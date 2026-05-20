from euclesia.rules.helpers import RuleHelper
from euclesia.rules.vanilla.adventure.a_throwaway_joke import a_throwaway_joke
from euclesia.rules.vanilla.adventure.adventuring_time import adventuring_time
from euclesia.rules.vanilla.adventure.arbalistic import arbalistic
from euclesia.rules.vanilla.adventure.blowback import blowback
from euclesia.rules.vanilla.adventure.bullseye import bullseye
from euclesia.rules.vanilla.adventure.careful_restoration import careful_restoration
from euclesia.rules.vanilla.adventure.caves_and_cliffs import caves_and_cliffs
from euclesia.rules.vanilla.adventure.country_lode_take_me_home import country_lode_take_me_home
from euclesia.rules.vanilla.adventure.crafters_crafting_crafters import crafters_crafting_crafters
from euclesia.rules.vanilla.adventure.crafting_a_new_look import crafting_a_new_look
from euclesia.rules.vanilla.adventure.heart_transplanter import heart_transplanter
from euclesia.rules.vanilla.adventure.hero_of_the_village import hero_of_the_village
from euclesia.rules.vanilla.adventure.hired_help import hired_help
from euclesia.rules.vanilla.adventure.is_it_a_balloon import is_it_a_balloon
from euclesia.rules.vanilla.adventure.is_it_a_bird import is_it_a_bird
from euclesia.rules.vanilla.adventure.is_it_a_plane import is_it_a_plane
from euclesia.rules.vanilla.adventure.isnt_it_scute import isnt_it_scute
from euclesia.rules.vanilla.adventure.it_spreads import it_spreads
from euclesia.rules.vanilla.adventure.light_as_a_rabbit import light_as_a_rabbit
from euclesia.rules.vanilla.adventure.lighten_up import lighten_up
from euclesia.rules.vanilla.adventure.mob_kabob import mob_kabob
from euclesia.rules.vanilla.adventure.monster_hunter import monster_hunter
from euclesia.rules.vanilla.adventure.monsters_hunted import monsters_hunted
from euclesia.rules.vanilla.adventure.ol_betsy import ol_betsy
from euclesia.rules.vanilla.adventure.over_overkill import over_overkill
from euclesia.rules.vanilla.adventure.postmortal import postmortal
from euclesia.rules.vanilla.adventure.respecting_the_remnants import respecting_the_remnants
from euclesia.rules.vanilla.adventure.revaulting import revaulting
from euclesia.rules.vanilla.adventure.smithing_with_style import smithing_with_style
from euclesia.rules.vanilla.adventure.sniper_duel import sniper_duel
from euclesia.rules.vanilla.adventure.sound_of_music import sound_of_music
from euclesia.rules.vanilla.adventure.star_trader import star_trader
from euclesia.rules.vanilla.adventure.sticky_situation import sticky_situation
from euclesia.rules.vanilla.adventure.surge_protector import surge_protector
from euclesia.rules.vanilla.adventure.sweet_dreams import sweet_dreams
from euclesia.rules.vanilla.adventure.take_aim import take_aim
from euclesia.rules.vanilla.adventure.the_power_of_books import the_power_of_books
from euclesia.rules.vanilla.adventure.trial_edition import trial_edition
from euclesia.rules.vanilla.adventure.two_birds_one_arrow import two_birds_one_arrow
from euclesia.rules.vanilla.adventure.under_lock_and_key import under_lock_and_key
from euclesia.rules.vanilla.adventure.very_very_frightening import very_very_frightening
from euclesia.rules.vanilla.adventure.voluntary_exile import voluntary_exile
from euclesia.rules.vanilla.adventure.what_a_deal import what_a_deal
from euclesia.rules.vanilla.adventure.who_is_the_pillager_now import who_is_the_pillager_now
from euclesia.rules.vanilla.adventure.sneak_100 import sneak_100
from euclesia.rules.vanilla.adventure.who_needs_rockets import who_needs_rockets


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
