package fr.euclesia.mcarchipelago.archipelago;

import fr.euclesia.mcarchipelago.protocol.APItemsHandling;
import fr.euclesia.mcarchipelago.protocol.APVersion;

import java.util.List;
import java.util.UUID;

public record APConnectionOptions(
        String game,
        String playerName,
        String password,
        APVersion version,
        int itemsHandling,
        List<String> tags,
        String uuid
) {
    public static APConnectionOptions minecraft(String playerName, String password) {
        return new APConnectionOptions(
                "AEMinecraft",
                playerName,
                password,
                APVersion.V0_6_0,
                APItemsHandling.ALL,
                List.of(),
                UUID.randomUUID().toString()
        );
    }
}
