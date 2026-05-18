from enum import Enum


class MCRegion(str, Enum):
    MENU        = "Menu"
    OVERWORLD   = "Overworld"
    NETHER      = "Nether"
    THE_END     = "The End"
