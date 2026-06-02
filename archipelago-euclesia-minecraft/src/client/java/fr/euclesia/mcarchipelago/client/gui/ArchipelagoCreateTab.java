package fr.euclesia.mcarchipelago.client.gui;

import fr.euclesia.mcarchipelago.client.connect.APConnectConfig;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.components.StringWidget;
import net.minecraft.client.gui.components.tabs.GridLayoutTab;
import net.minecraft.client.gui.layouts.GridLayout;
import net.minecraft.client.gui.layouts.LayoutSettings;
import net.minecraft.client.gui.Font;
import net.minecraft.network.chat.Component;

/**
 * The "Archipelago" tab on the create-world screen: address / port / slot / password fields whose
 * values are saved into the new world's folder (see {@code APWorldConnection}) so joining the world
 * connects to that slot. Fields are pre-filled from the last-used global config for convenience.
 */
public final class ArchipelagoCreateTab extends GridLayoutTab {
    private static final Component TITLE = Component.literal("Archipelago");
    private static final int FIELD_WIDTH = 200;
    private static final int FIELD_HEIGHT = 20;

    private final EditBox address;
    private final EditBox port;
    private final EditBox slot;
    private final EditBox password;

    public ArchipelagoCreateTab(Font font) {
        super(TITLE);
        APConnectConfig config = APConnectConfig.get();

        GridLayout.RowHelper rows = this.layout.rowSpacing(8).createRowHelper(2);
        rows.addChild(new StringWidget(Component.literal("Connect this world to Archipelago"), font), 2);

        this.address = addRow(rows, font, "Address", "archipelago.gg", config.address);
        this.port = addRow(rows, font, "Port", "38281", config.port);
        this.slot = addRow(rows, font, "Slot name", "Slot name", config.slot);
        this.password = addRow(rows, font, "Password", "(optional)", config.password);
    }

    private static EditBox addRow(GridLayout.RowHelper rows, Font font, String label, String hint, String value) {
        LayoutSettings labelSettings = rows.newCellSettings().alignVerticallyMiddle();
        rows.addChild(new StringWidget(Component.literal(label), font), labelSettings);

        EditBox field = new EditBox(font, FIELD_WIDTH, FIELD_HEIGHT, Component.literal(label));
        field.setMaxLength(256);
        field.setHint(Component.literal(hint));
        field.setValue(value == null ? "" : value);
        rows.addChild(field);
        return field;
    }

    public String address() {
        return address.getValue();
    }

    public String port() {
        return port.getValue();
    }

    public String slot() {
        return slot.getValue();
    }

    public String password() {
        return password.getValue();
    }
}
