package fr.euclesia.mcarchipelago.client.dump;

import net.minecraft.server.packs.PackResources;
import net.minecraft.server.packs.PackType;
import net.minecraft.server.packs.repository.PackRepository;
import net.minecraft.server.packs.repository.ServerPacksSource;
import net.minecraft.server.packs.resources.CloseableResourceManager;
import net.minecraft.server.packs.resources.MultiPackResourceManager;

import java.util.List;

/**
 * Opens a standalone SERVER_DATA {@link CloseableResourceManager} over the selected datapacks
 * (vanilla + any mod datapacks Fabric injects) WITHOUT a world or integrated server. This lets the
 * title-menu dump read the raw datapack JSON (recipes / loot / advancements / tags / structure NBT)
 * the same way the offline {@code tools/build_*.py} read the jar. Caller must close the result.
 */
public final class DumpDataSource {
    private DumpDataSource() {}

    public static CloseableResourceManager openServerData() {
        PackRepository repository = ServerPacksSource.createVanillaTrustedRepository();
        repository.reload();
        repository.setSelected(repository.getAvailableIds());
        List<PackResources> packs = repository.openAllSelected();
        return new MultiPackResourceManager(PackType.SERVER_DATA, packs);
    }
}
