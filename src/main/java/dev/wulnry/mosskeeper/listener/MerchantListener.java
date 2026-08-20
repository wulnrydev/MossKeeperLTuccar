package dev.wulnry.mosskeeper.listener;

import dev.wulnry.mosskeeper.MossKeeperLTuccar;
import dev.wulnry.mosskeeper.data.TradeProduct;
import dev.wulnry.mosskeeper.gui.MerchantMenu;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.inventory.MerchantInventory;
import org.bukkit.scheduler.BukkitRunnable;

/**
 * Köylü tarzi (vanilla Merchant) ekranin takip katmani.
 * <p>
 * Esya alisverisine <b>karismayiz</b>: alma/verme islemini vanilla yapar,
 * dolayisiyla dupe/rollback riski olusturacak ek bir envanter mudahalesi
 * yoktur. Burada yalnizca takasin gerceklestigini tespit edip mesaj/ses
 * veriyoruz. Tespit, teklifin {@code uses} sayacini tiklamadan once ve
 * bir tick sonra karsilastirarak yapilir - shift+tik ile yapilan coklu
 * takaslar da dogru sayilir.
 */
public final class MerchantListener implements Listener {

    private final MossKeeperLTuccar plugin;
    private final MerchantMenu merchantMenu;

    public MerchantListener(MossKeeperLTuccar plugin, MerchantMenu merchantMenu) {
        this.plugin = plugin;
        this.merchantMenu = merchantMenu;
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onClick(InventoryClickEvent event) {
        if (event.getRawSlot() != MerchantMenu.RESULT_SLOT) {
            return;
        }
        if (!(event.getWhoClicked() instanceof Player player)) {
            return;
        }
        if (!(event.getInventory() instanceof MerchantInventory merchantInventory)) {
            return;
        }

        MerchantMenu.Session session = merchantMenu.session(player);
        // Baska bir eklentinin/koylunun ekrani ise dokunma.
        if (session == null || merchantInventory.getMerchant() != session.getMerchant()) {
            return;
        }

        final int index = merchantInventory.getSelectedRecipeIndex();
        final TradeProduct product = session.productAt(index);
        if (product == null) {
            return;
        }
        final int unitCost = session.effectiveCostAt(index);
        if (unitCost < 0) {
            return;
        }
        final int usesBefore = session.usesOf(index);
        if (usesBefore < 0) {
            return;
        }

        // Takas vanilla tarafindan bu tick icinde tamamlanir; sonucu bir
        // sonraki tick'te okuruz.
        new BukkitRunnable() {
            @Override
            public void run() {
                MerchantMenu.Session current = merchantMenu.session(player);
                if (current != session) {
                    return;
                }
                int delta = current.usesOf(index) - usesBefore;
                if (delta > 0) {
                    merchantMenu.announceTrade(player, product, unitCost, delta);
                }
            }
        }.runTask(plugin);
    }

    @EventHandler
    public void onClose(InventoryCloseEvent event) {
        merchantMenu.closeSession(event.getPlayer().getUniqueId());
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        merchantMenu.closeSession(event.getPlayer().getUniqueId());
    }
}
