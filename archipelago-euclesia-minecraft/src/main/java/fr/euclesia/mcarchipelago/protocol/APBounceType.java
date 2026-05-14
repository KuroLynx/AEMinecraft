package fr.euclesia.mcarchipelago.protocol;

import java.util.Arrays;
import java.util.List;

public enum APBounceType {
    DEATH_LINK("DeathLink");

    private final String tag;

    APBounceType(String tag) {
        this.tag = tag;
    }

    public String tag() {
        return tag;
    }

    public static List<String> tags(APBounceType... types) {
        return Arrays.stream(types).map(APBounceType::tag).toList();
    }
}
