from BaseClasses import Item, Location, Region, Tutorial
from worlds.AutoWorld import WebWorld, World
from .data import *
from .options import EuclesiaOptions, Goals
from .regions import EuclesiaRegion


# ---------------------------------------------------------------------------
# Classes Item et Location
# ---------------------------------------------------------------------------

class EuclesiaItem(Item):
    game = "Minecraft"


class EuclesiaLocation(Location):
    game = "Minecraft"


# ---------------------------------------------------------------------------
# WebWorld
# ---------------------------------------------------------------------------

class EuclesiaWebWorld(WebWorld):
    theme = "dirt"
    tutorials = [
        Tutorial(
            tutorial_name = "Setup Guide",
            description = "Guide to set up the Euclesia Minecraft randomizer.",
            language = "English",
            file_name = "setup_en.md",
            link = "setup/en",
            authors = ["KuroLynx"],
        )
    ]


# ---------------------------------------------------------------------------
# World principal
# ---------------------------------------------------------------------------

class EuclesiaWorld(World):
    """Minecraft Randomizer by KuroLynx (Mod by EDGN)"""
    game = "Minecraft"
    options: EuclesiaOptions
    options_dataclass = EuclesiaOptions
    web: WebWorld = EuclesiaWebWorld()
    death_list: list[str] = []

    item_name_to_id = {name: data.id for (name, data) in ITEMS.items() if name != "Victory"}
    location_name_to_id = {name: data.id for (name, data) in ALL_LOCATIONS.items() if name != "Victory"}

    # -----------------------------------------------------------------------
    # Génération
    # -----------------------------------------------------------------------

    def create_item(self, name: str) -> EuclesiaItem:
        item_data: ItemData = ITEMS[name]
        return EuclesiaItem(name, item_data.classification, item_data.id, self.player)

    def generate_early(self) -> None:
        """Génère les données aléatoires qui doivent être disponibles dès set_rules."""
        if self.options.death_list:
            count = min(self.options.death_list_count.value, len(MOBS_ALL))
            self.death_list: list[str] = self.random.sample(list(MOBS_ALL.keys()), count)

    def _get_active_locations(self) -> dict[str, LocationData]:
        """Retourne les locations actives selon les options du joueur."""
        locations: dict[str, LocationData] = {
            **LOCATIONS_ADVANCEMENT,
            **LOCATIONS_BOSS_KILLS,
        }

        if self.options.kill_sanity:
            locations.update(LOCATIONS_MOB_KILLS)

        return locations

    def create_regions(self) -> None:
        added_regions: dict[str, Region] = {}

        for region in EuclesiaRegion:
            added_regions[region] = Region(region, self.player, self.multiworld)

        for loc_name, loc_data in self._get_active_locations().items():
            region = added_regions[EuclesiaRegion(loc_data.region)]
            location = EuclesiaLocation(self.player, loc_name, loc_data.id, region)
            region.locations.append(location)

        added_regions[EuclesiaRegion.MENU].connect(added_regions[EuclesiaRegion.OVERWORLD])

        added_regions[EuclesiaRegion.OVERWORLD].connect(
            added_regions[EuclesiaRegion.NETHER],
            rule = lambda state: state.has("Dimension Unlock: Nether", self.player),
        )

        added_regions[EuclesiaRegion.OVERWORLD].connect(
            added_regions[EuclesiaRegion.THE_END],
            rule = lambda state: state.has("Dimension Unlock: The End", self.player),
        )

        # If needed to add new dimensions (like TP), do it here i guess

        self.multiworld.regions += list(added_regions.values())

    def create_items(self) -> None:
        pool: list[EuclesiaItem] = []

        for name, item_data in ITEMS.items():
            if name == "Victory":
                continue

            if not self.options.villager_trust and name == "Progressive Villager Trust":
                continue

            for _ in range(item_data.count):
                pool.append(self.create_item(name))

        # Complétion de la pool avec filler
        active_location_count = len(self._get_active_locations())
        while len(pool) < active_location_count:
            pool.append(self.create_item("Bread"))

        self.multiworld.itempool += pool[:active_location_count]

    def set_rules(self) -> None:
        self._set_goal_rule()

    def generate_bread(self) -> None:
        victory = EuclesiaItem("Victory", ItemClassification.progression, None, self.player)

        match self.options.goals:
            case Goals.option_all_bosses:
                selected_bosses = self.options.boss_selection.value
                goal_location = next(
                    (f"Kill Boss: {name}" for name in reversed(list(MOBS_BOSS.keys())) if name in selected_bosses)
                )
            # Using of default _ pattern to not lock generation if a valid goal was not selected (always at the end of
            # all cases)
            case Goals.option_vanilla | _:
                goal_location = "Kill Boss: Ender Dragon"

        self.multiworld.get_location(goal_location, self.player).place_locked_item(victory)


    # -----------------------------------------------------------------------
    # Goal
    # -----------------------------------------------------------------------

    def _get_primary_condition(self):
        player = self.player

        match self.options.goals:
            case Goals.option_all_bosses:
                selected_bosses = self.options.boss_selection.value
                bosses_location = [f"Kill Boss: {name}" for name in list(MOBS_BOSS.keys()) if name in selected_bosses]
                return lambda state: all(state.can_reach(boss_location, "Location", player) for boss_location in bosses_location)

            #Using of default _ pattern to not lock generation if a valid goal was not selected (always at the end of
            # all cases)
            case Goals.option_vanilla | _:
                return lambda state: state.can_reach("Kill Boss: Ender Dragon", player)

    def _set_goal_rule(self) -> None:
        player = self.player
        primary_condition = self._get_primary_condition()

        if self.options.death_list:
            death_list_locations = [f"Kill Entity: {mob_name}" for mob_name in self.death_list]

            def completion_condition(state) -> bool:
                return primary_condition(state) and all(state.can_reach(location, "Location", player) for location in death_list_locations)

            self.multiworld.completion_condition[player] = completion_condition
        else:
            self.multiworld.completion_condition[player] = primary_condition

    # -----------------------------------------------------------------------
    # Slot data (envoyé au mod Fabric)
    # -----------------------------------------------------------------------

    def fill_slot_data(self) -> dict:
        slot_data = {
            "goal"                 : self.options.goals.value,
            "boss_selection"       : list(self.options.boss_selection.value),
            "death_link"           : bool(self.options.death_link.value),
            "villager_trust"       : bool(self.options.villager_trust.value),
            "kill_sanity"          : bool(self.options.kill_sanity.value),
            "death_list"           : bool(self.options.death_list.value),
            "death_list_count"     : self.options.death_list_count.value,
            "advancements_required": self.options.advancements_required.value,
            "advancement_locations": {
                advancement_data.game_id: advancement_data.id
                for advancement_data in LOCATIONS_ADVANCEMENT.values()
            },
            "boss_locations"       : {
                boss_kill_data.game_id: boss_kill_data.id
                for boss_kill_data in LOCATIONS_BOSS_KILLS.values()
            },
            "death_list_mobs"      : self.death_list,
        }

        return slot_data