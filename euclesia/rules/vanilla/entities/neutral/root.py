from worlds.euclesia.rules.helpers import RuleHelper
from .bee import bee
from .cave_spider import cave_spider
from .dolphin import dolphin
from .drowned import drowned
from .enderman import enderman
from .fox import fox
from .goat import goat
from .iron_golem import iron_golem
from .llama import llama
from .nautilus import nautilus
from .panda import panda
from .piglin import piglin
from .polar_bear import polar_bear
from .pufferfish import pufferfish
from .spider import spider
from .trader_llama import trader_llama
from .wolf import wolf
from .zombie_nautilus import zombie_nautilus
from .zombified_piglin import zombified_piglin


def get_neutral_rules(helper: RuleHelper) -> dict:
    return (
        bee(helper) |
        cave_spider(helper) |
        dolphin(helper) |
        drowned(helper) |
        enderman(helper) |
        fox(helper) |
        goat(helper) |
        iron_golem(helper) |
        llama(helper) |
        nautilus(helper) |
        panda(helper) |
        piglin(helper) |
        polar_bear(helper) |
        pufferfish(helper) |
        spider(helper) |
        trader_llama(helper) |
        wolf(helper) |
        zombie_nautilus(helper) |
        zombified_piglin(helper)
    )
