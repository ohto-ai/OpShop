package com.ohtoai.opshop.gui;

import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.inventory.InventoryHolder;

/**
 * Interface for GUI holders that handle their own click events.
 */
public interface GuiHolder extends InventoryHolder {

    /**
     * Handle a click in this GUI. Called by GuiListener.
     */
    void handleClick(InventoryClickEvent event);

    /**
     * Refresh the inventory contents. Called when state changes.
     */
    void refresh();
}
