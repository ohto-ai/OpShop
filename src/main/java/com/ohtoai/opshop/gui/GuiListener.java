package com.ohtoai.opshop.gui;

import com.ohtoai.opshop.OpShopPlugin;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.event.inventory.InventoryType;

/**
 * Single listener that dispatches inventory clicks to the active GuiHolder.
 */
public class GuiListener implements Listener {

    private final OpShopPlugin plugin;

    public GuiListener(OpShopPlugin plugin) {
        this.plugin = plugin;
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onInventoryClick(InventoryClickEvent event) {
        // Ghost item protection: intercept actions that would drop a picked-up editor item
        ShopEditorGui editor = ShopEditorGui.getActiveInstance((org.bukkit.entity.Player) event.getWhoClicked());
        if (editor != null && editor.hasPickedUpItem()) {
            if (event.getClickedInventory() == null
                || event.getClick() == org.bukkit.event.inventory.ClickType.DROP
                || event.getClick() == org.bukkit.event.inventory.ClickType.CONTROL_DROP) {
                event.setCancelled(true);
                editor.cancelPickup((org.bukkit.entity.Player) event.getWhoClicked());
                return;
            }
        }

        if (event.getClickedInventory() == null) return;

        // Anvil input for ShopEditorGui — intercept before GuiHolder check
        if (event.getView().getTopInventory().getType() == InventoryType.ANVIL) {
            ShopEditorGui ed = ShopEditorGui.getActiveInstance((org.bukkit.entity.Player) event.getWhoClicked());
            if (ed != null && ed.isAnvilOpen()) {
                event.setCancelled(true);
                if (event.getClickedInventory() == event.getView().getTopInventory()) {
                    ed.handleAnvilClick((org.bukkit.entity.Player) event.getWhoClicked(), event);
                }
                return;
            }
        }

        if (!(event.getView().getTopInventory().getHolder() instanceof GuiHolder holder)) return;

        boolean isEditor = holder instanceof ShopEditorGui;

        if (isEditor) {
            // Editor: allow bottom-inventory clicks (pickup), cancel top-inventory clicks
            // EXCEPT when dropping an item on the "add" slot (47) with cursor
            if (event.getClickedInventory() != event.getView().getTopInventory()) {
                // Bottom inventory: let player pick up items freely
                return;
            }
            // Top inventory: cancel unless cursor has item (allow drops anywhere), or it's a creative copy
            boolean hasCursorItem = event.getCursor() != null && event.getCursor().getType() != org.bukkit.Material.AIR;
            boolean isCreativeCopy = event.getClick().isCreativeAction();
            if (!isCreativeCopy && !hasCursorItem) {
                event.setCancelled(true);
            }
        } else {
            // Non-editor GUIs: cancel all clicks except creative middle-click copy
            if (!event.getClick().isCreativeAction()) {
                event.setCancelled(true);
            }
        }

        // Route top-inventory clicks to holder
        if (event.getClickedInventory() == event.getView().getTopInventory()) {
            holder.handleClick(event);
        }
    }

    @EventHandler(priority = EventPriority.NORMAL)
    public void onInventoryDrag(InventoryDragEvent event) {
        if (event.getView().getTopInventory().getHolder() instanceof GuiHolder
            && !(event.getView().getTopInventory().getHolder() instanceof ShopEditorGui)) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.NORMAL)
    public void onInventoryClose(InventoryCloseEvent event) {
        // Ghost item protection: restore picked-up item data if editor closes unexpectedly.
        // Do NOT cancelPickup (which rebuilds & reopens inventory). Just restore data silently.
        ShopEditorGui ed = ShopEditorGui.getActiveInstance((org.bukkit.entity.Player) event.getPlayer());
        if (ed != null && ed.hasPickedUpItem()) {
            ed.silentRestorePickup();
        }

        // Anvil close — finalize item creation from the input name
        if (event.getView().getTopInventory().getType() == InventoryType.ANVIL) {
            ShopEditorGui anvilEditor = ShopEditorGui.getActiveInstance((org.bukkit.entity.Player) event.getPlayer());
            if (anvilEditor != null && anvilEditor.isAnvilOpen()) {
                anvilEditor.handleAnvilClose((org.bukkit.entity.Player) event.getPlayer(), event.getInventory());
            }
        }
    }
}
