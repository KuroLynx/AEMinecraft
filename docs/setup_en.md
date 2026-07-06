# Setup Guide — Minecraft [AEM]

*Minecraft Randomizer by KuroLynx (mod by EDGN)*

---

## Required Software

| Component | Version | Download |
| --- | --- | --- |
| **Minecraft: Java Edition** | `26.1.2` | — |
| **Fabric Loader** | `0.19.2` or newer | [fabricmc.net](https://fabricmc.net/use/installer/) |
| **Fabric API** | `0.149.0+26.1.2` | [github.com/FabricMC/fabric-api](https://github.com/FabricMC/fabric-api/releases) |
| **Minecraft [AEM] mod** (`.jar`) | latest release | [Latest release](https://github.com/KuroLynx/ArchipelagoMinecraft/releases/latest) |
| **Minecraft [AEM] apworld** (`.apworld`) | latest release | [Latest release](https://github.com/KuroLynx/ArchipelagoMinecraft/releases/latest) |
| **Archipelago** | `0.6.7` or newer | [archipelago.gg](https://archipelago.gg/) |

> Download the `.jar` and `.apworld` from the **same** release so their versions match.

## Optional Software

| Component | Compatible Version | Download |
| --- | --- | --- |
| **BlazeAndCave's Advancement Pack (BACAP)** | `1.20.3` | [Download (MediaFire)](https://www.mediafire.com/file/z5x3r2gzscrd4ui/BlazeandCave%2527s_Advancements_Pack_1.20.3.zip/file) |

---

## Installation

1. Install **Fabric Loader** for Minecraft `26.1.2`.
2. Drop the following into your `.minecraft/mods/` folder:
   - the **Minecraft [AEM]** mod `.jar`
   - **Fabric API**
3. Place the **Minecraft [AEM]** `.apworld` in your Archipelago installation
   (double-clicking the `.apworld` with the Archipelago Launcher installed will install it for you).

---

## Creating a Config File (`.yaml`)

You need a player YAML (your "config") before you can generate or join a game. Pick either method.

### Option A — From a template (recommended)

1. Download the **`template.yaml`** attached to the [latest release](https://github.com/KuroLynx/ArchipelagoMinecraft/releases/latest).
2. Edit it to your liking — every option is documented with inline comments.

### Option B — With the Archipelago Options tool

*(Requires the Archipelago Launcher, with the AEM `.apworld` installed.)*

Use the **options / template generator** in your Archipelago Launcher to produce a valid YAML for
**Minecraft [AEM]**, then customize it.

---

## Joining a MultiWorld Game

### First time (creating your world)

1. Launch Minecraft with the required mods installed (**Minecraft [AEM]** + **Fabric API**).
2. Click **Singleplayer**, then **Create New World**.
3. Open the **Archipelago** tab (far right of the tab bar at the top of the create-world screen).
4. Enter the **Address**, **Port**, **Slot name**, and **Password** (if the room needs one) into the
   correct fields.
5. **Install any datapacks your config requires** (e.g. BACAP). Still on the create-world screen:
   1. Open the **More** tab.
   2. Click **Data Packs**.
   3. If your datapack isn't listed, click **Open Pack Folder**.
      1. Copy (or drag & drop) your datapack into the folder that opens.
      2. Return to the game — your datapack now appears under **Available**.
   4. Hover the datapack and click the arrow to move it to **Selected**.
   5. Click **Done**.
6. *(Optional)* Adjust any game rules you want under the **More** tab.
7. Click **Create New World**.

> **Note:** if the connection to the Archipelago server is lost, you'll be returned to the main
> title screen.

### After the first time (rejoining)

1. Launch Minecraft with the required mods installed.
2. Click **Singleplayer**.
3. Open your existing world — it reconnects to its Archipelago slot automatically.

> Each Archipelago world is tied to the slot it was created with. The most recently created world is
> the one at the top of your Singleplayer list.
