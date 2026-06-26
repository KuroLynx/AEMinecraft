package fr.euclesia.mcarchipelago.client.dump;

import net.minecraft.network.chat.Component;
import net.minecraft.server.packs.PackResources;
import net.minecraft.server.packs.PackType;
import net.minecraft.server.packs.repository.FolderRepositorySource;
import net.minecraft.server.packs.repository.Pack;
import net.minecraft.server.packs.repository.PackRepository;
import net.minecraft.server.packs.repository.PackSource;
import net.minecraft.server.packs.repository.ServerPacksSource;
import net.minecraft.server.packs.resources.CloseableResourceManager;
import net.minecraft.server.packs.resources.MultiPackResourceManager;
import net.minecraft.world.level.validation.DirectoryValidator;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Set;

/**
 * Opens a standalone SERVER_DATA {@link CloseableResourceManager} over the selected datapacks
 * (vanilla + any mod datapacks Fabric injects) WITHOUT a world or integrated server. This lets the
 * title-menu dump read the raw datapack JSON (recipes / loot / advancements / tags / structure NBT)
 * the same way the offline {@code tools/build_*.py} read the jar. Caller must close the result.
 *
 * <p>World datapacks (e.g. BACAP) live in {@code <world>/datapacks/} and are NOT visible to the
 * vanilla trusted repository. Drop them in a folder and {@link #availableDatapacks} lists them so the
 * UI can offer per-pack selection; {@link #openServerData(Path, Collection)} then folds only the
 * chosen ones in AFTER vanilla so they correctly override it, mirroring how a world stacks datapacks.
 */
public final class DumpDataSource {
    private DumpDataSource() {}

    /** A selectable datapack found in the source folder: repository id, display title and description. */
    public record DatapackInfo(String id, Component title, Component description) {}

    /** PathMatcher (p -> true) trusts the folder's symlinks, same as the vanilla trusted repo. */
    private static DirectoryValidator trustAll() {
        return new DirectoryValidator(path -> true);
    }

    private static PackRepository folderRepository(Path dir) {
        PackRepository repository = new PackRepository(new FolderRepositorySource(
                dir, PackType.SERVER_DATA, PackSource.WORLD, trustAll()));
        repository.reload();
        return repository;
    }

    private static DatapackInfo info(Pack pack) {
        return new DatapackInfo(pack.getId(), pack.getTitle(), pack.getDescription());
    }

    /**
     * Every selectable SERVER_DATA pack: vanilla, the Fabric mod datapacks (Twilight Forest, AoA, …),
     * and any loose folder / {@code .zip} datapacks (BACAP) dropped in {@code dir}. Mods are not
     * exempt — they show up like any other pack and can simply be deselected.
     */
    public static List<DatapackInfo> availableDatapacks(Path dir) {
        List<DatapackInfo> out = new ArrayList<>();
        PackRepository vanilla = ServerPacksSource.createVanillaTrustedRepository();
        vanilla.reload();
        for (Pack pack : vanilla.getAvailablePacks()) {
            out.add(info(pack));
        }
        if (dir != null && Files.isDirectory(dir)) {
            for (Pack pack : folderRepository(dir).getAvailablePacks()) {
                out.add(info(pack));
            }
        }
        return out;
    }

    public static CloseableResourceManager openServerData() {
        return openServerData(null, Set.of());
    }

    /** Open a SERVER_DATA manager over exactly the chosen packs (across vanilla, mods and the folder). */
    public static CloseableResourceManager openServerData(Path extraDatapacksDir, Collection<String> selectedPackIds) {
        List<PackResources> packs = new ArrayList<>();

        PackRepository vanilla = ServerPacksSource.createVanillaTrustedRepository();
        vanilla.reload();
        packs.addAll(selectFrom(vanilla, selectedPackIds));

        if (extraDatapacksDir != null && Files.isDirectory(extraDatapacksDir)) {
            packs.addAll(selectFrom(folderRepository(extraDatapacksDir), selectedPackIds));  // appended last
        }

        return new MultiPackResourceManager(PackType.SERVER_DATA, packs);
    }

    private static List<PackResources> selectFrom(PackRepository repository, Collection<String> selectedPackIds) {
        List<String> chosen = new ArrayList<>(repository.getAvailableIds());
        chosen.retainAll(selectedPackIds);
        if (chosen.isEmpty()) {
            return List.of();
        }
        repository.setSelected(chosen);
        return repository.openAllSelected();
    }
}
