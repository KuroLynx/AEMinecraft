# Versioning & Releases

This project ships **two independent artifacts** that evolve at different rates, so they are
versioned and released **separately**:

- the **mod** — the Fabric mod jar (`aem-<version>.jar`)
- the **apworld** — the Archipelago world (`minecraft_aem-<version>.apworld`)

There is no single project version. Instead there are three version axes, each with its own source
of truth. Releases are cut by pushing a git tag; CI builds the artifact and publishes a GitHub
Release. **The file is always the source of truth and the tag must mirror it** — CI fails the
release if they disagree, so a tag can never claim a version the files don't.

## The three version axes

| Axis | Source of truth | Bump when… |
|------|-----------------|------------|
| **apworld** logic/content | `world_version` in [`minecraft_aem/archipelago.json`](../minecraft_aem/archipelago.json) | world rules, items, or locations change |
| **mod** features | `mod_version` in [`archipelago-euclesia-minecraft/gradle.properties`](../archipelago-euclesia-minecraft/gradle.properties) | mod behavior changes |
| **target Minecraft** | `minecraft_version` in the same `gradle.properties` | the mod is retargeted to a new Minecraft version |

### Mod version format

The mod's release version pairs its feature version with the Minecraft version it targets (a Fabric
convention), composed in `build.gradle`:

```groovy
version = "${project.mod_version}+${project.minecraft_version}"   // e.g. 1.0.0+26.1.2
```

This is why a **Minecraft-only bump ships without touching `mod_version` or the apworld**: changing
`minecraft_version` from `26.1.2` to `26.1.3` yields a new build `1.0.0+26.1.3` on its own.

## Tag conventions

| Tag pattern | Releases | Example |
|-------------|----------|---------|
| `mod-v<mod_version>+<minecraft_version>` | the mod jar | `mod-v1.0.0+26.1.3` |
| `apworld-v<world_version>` | the apworld | `apworld-v1.1.0` |

Each tag pattern triggers only its own workflow
([`release-mod.yml`](../.github/workflows/release-mod.yml),
[`release-apworld.yml`](../.github/workflows/release-apworld.yml)), so the two artifacts release
independently.

## Release procedures

### Retarget Minecraft (mod only — apworld untouched)

```bash
# gradle.properties: minecraft_version=26.1.3   (plus any deps/mappings the new MC needs)
git commit -am "chore: target Minecraft 26.1.3"
git tag mod-v1.0.0+26.1.3
git push origin mod-v1.0.0+26.1.3
```

Releases `aem-1.0.0+26.1.3.jar`. `mod_version` is unchanged and the apworld is never rebuilt.

### Mod feature change

```bash
# gradle.properties: mod_version=1.1.0
git commit -am "feat: <what changed>"
git tag mod-v1.1.0+26.1.2
git push origin mod-v1.1.0+26.1.2
```

### Apworld change

```bash
# minecraft_aem/archipelago.json: "world_version": "1.1.0"
git commit -am "feat(world): <what changed>"
git tag apworld-v1.1.0
git push origin apworld-v1.1.0
```

### A change spanning both

Features that touch both the mod and the world (e.g. a new item that is also an AP item) bump both
sources of truth and get **both** tags:

```bash
git tag mod-v1.1.0+26.1.2
git tag apworld-v1.1.0
git push origin mod-v1.1.0+26.1.2 apworld-v1.1.0
```

## What CI does

On a matching tag push, the workflow:

1. Reads the version from the source-of-truth file.
2. **Verifies the tag matches it**, and fails with a clear error if not — e.g. pushing
   `mod-v1.1.0+26.1.2` while `gradle.properties` still says `mod_version=1.0.0` aborts the release.
3. Builds the artifact (mod: JDK 25 + Gradle; apworld: zips `minecraft_aem/`, excluding
   `__pycache__`/`.pyc`).
4. Uploads it as a workflow artifact, then publishes a GitHub Release named after the tag with
   auto-generated notes and the artifact attached.

### Dry runs

Run either workflow manually from the **Actions** tab (`workflow_dispatch`) to build/package the
artifact and upload it as a workflow artifact **without** publishing a release. Useful for checking
a build before tagging.

## Notes

- **apworld naming.** The world package is `minecraft_aem/` (not `minecraft/`) so the shipped
  `minecraft_aem.apworld` does not collide with Archipelago's built-in Minecraft world when a user
  installs it. If you develop against a local Archipelago checkout, install the world there as
  `worlds/minecraft_aem/` to match (the `tools/` scripts import `worlds.minecraft_aem`).
- **`archipelago.json`.** Its `game` (`"Minecraft [AEM]"`) must match the world's `game` in
  `minecraft_aem/__init__.py`, and `minimum_ap_version` is the oldest Archipelago version the world
  supports.
## Mod ↔ apworld compatibility

The mod and apworld share a **slot-data schema version**, so a mismatched pair is caught at connect
time instead of failing mysteriously in-game:

- The apworld advertises `SLOT_DATA_VERSION` (in `minecraft_aem/__init__.py`) as `slot_data_version`
  in slot data.
- The mod declares the range of schema versions it understands
  (`CompatibilityService.MIN_SUPPORTED..MAX_SUPPORTED`).
- On connect, if the world's version is **newer** than the mod supports, the mod refuses to enter the
  world (via the pre-flight `ArchipelagoConnectingScreen`) with *"update the mod"*; if it's **older**
  than the mod's minimum, it says *"regenerate with a newer apworld"*. Slot data with no version field
  (pre-versioning seeds) is treated as legacy and allowed.

This version is **independent of the release versions above** — it changes far less often. Bump it
only on a *breaking* slot-data change:

- **`SLOT_DATA_VERSION`** (apworld) — increment when `fill_slot_data()` changes in a way an older mod
  can't read (a renamed/removed field, or a new field the mod must have). Purely additive fields the
  mod tolerates when absent do **not** need a bump.
- **`CompatibilityService.MAX_SUPPORTED`** (mod) — increment when the mod is updated to read that new
  schema. Raise `MIN_SUPPORTED` only when dropping support for an old schema.

A breaking change therefore touches both sides in the same release: bump `SLOT_DATA_VERSION`, teach
the mod to read it, and bump `MAX_SUPPORTED`.
