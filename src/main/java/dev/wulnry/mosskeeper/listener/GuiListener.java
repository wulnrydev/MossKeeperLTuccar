package dev.wulnry.mosskeeper.listener;

import dev.wulnry.mosskeeper.gui.GuiManager;
import dev.wulnry.mosskeeper.gui.TraderMenuHolder;
import org.bukkit.entity.Player;
import org.bukkit.event.Event;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.ClickType;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.inventory.Inventory;

/**
 * Menu guvenligi.
 * <p>
 * Esya calinmasi/duplike edilmesi ihtimalini kapatmak icin menu acikken
 * <b>tum</b> tiklama ve surukleme islemleri kosulsuz iptal edilir; bu, oyuncu
 * kendi envanterine shift+tiklasa veya sayi tuslarina bassa da gecerlidir.
 * <p>
 * Ust envantere {@code InventoryEvent#getInventory()} ile erisilir;
 * {@code getView()} kasten kullanilmaz (bkz. {@link GuiManager} - 1.21 uyumu).
 */
public final class GuiListener implements Listener {

    private final GuiManager guiManager;

    public GuiListener(GuiManager guiManager) {
        this.guiManager = guiManager;
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onClick(InventoryClickEvent event) {
        Inventory top = event.getInventory();
        if (!(top.getHolder() instanceof TraderMenuHolder holder)) {
            return;
        }

        // Menumuz acik: her turlu esya hareketini engelle.
        event.setCancelled(true);
        event.setResult(Event.Result.DENY);

        if (!(event.getWhoClicked() instanceof Player player)) {
            return;
        }
        // Sadece ust envanterdeki (menudeki) gecerli slotlar islenir.
        if (!top.equals(event.getClickedInventory())) {
            return;
        }
        ClickType click = event.getClick();
        if (click != ClickType.LEFT && click != ClickType.SHIFT_LEFT
                && click != ClickType.RIGHT && click != ClickType.SHIFT_RIGHT) {
            return;
        }

        guiManager.handleClick(player, holder, event.getRawSlot(), click.isShiftClick());
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onDrag(InventoryDragEvent event) {
        if (event.getInventory().getHolder() instanceof TraderMenuHolder) {
            event.setCancelled(true);
        }
    }

    @EventHandler
    public void onClose(InventoryCloseEvent event) {
        if (event.getInventory().getHolder() instanceof TraderMenuHolder) {
            guiManager.forgetMenu(event.getPlayer().getUniqueId());
        }
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        guiManager.forget(event.getPlayer().getUniqueId());
    }
}
