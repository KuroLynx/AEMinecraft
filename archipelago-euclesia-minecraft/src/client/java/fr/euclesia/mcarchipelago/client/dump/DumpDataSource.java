package fr.euclesia.mcarchipelago.client.dump;

import net.minecraft.network.chat.Component;
import net.minecraft.server.packs.PackType;
import net.minecraft.server.packs.repository.FolderRepositorySource;
import net.minecraft.server.packs.repository.Pack;
import net.minecraft.server.packs.repository.PackCompatibility;
import net.minecraft.server.packs.repository.PackRepository;
import net.minecraft.server.packs.repository.PackSource;
import net.minecraft.server.packs.repository.ServerPacksSource;
import net.minecraft.world.level.validation.DirectoryValidator;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * The datapacks the dump screen offers: vanilla, the mod datapacks Fabric injects, and the loose
 * folder / {@code .zip} datapacks (BACAP) dropped in the dump source folder, which the vanilla trusted
 * repository can't see. The dump itself runs in a throwaway world loaded with the ticked ones
 * ({@code HeadlessWorldDump}).
 */
public final class DumpDataSource {
    private DumpDataSource() {}

    /** A selectable datapack: repository id, display title, description, and whether it is made for this game. */
    public record DatapackInfo(String id, Component title, Component description, PackCompatibility compatibility) {}

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
        return new DatapackInfo(pack.getId(), pack.getTitle(), pack.getDescription(), pack.getCompatibility());
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
}
