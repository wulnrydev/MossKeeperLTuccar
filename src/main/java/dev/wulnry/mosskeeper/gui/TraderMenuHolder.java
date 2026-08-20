package dev.wulnry.mosskeeper.gui;

import dev.wulnry.mosskeeper.data.TradeProduct;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;

import java.util.HashMap;
import java.util.Map;

/**
 * Menu kimligi. Baslik metnini karsilastirmak yerine {@link InventoryHolder}
 * kullaniyoruz: renk kodlari/dil degisse bile menu dogru taninir ve sahte
 * baslikla menu taklit edilemez.
 * <p>
 * Slot -> urun eslesmesi burada tutuldugu icin tiklamada arama yapilmaz (O(1)).
 */
public final class TraderMenuHolder implements InventoryHolder {

    private final int page;
    private final int totalPages;
    private final Map<Integer, TradeProduct> slots = new HashMap<>();

    private Inventory inventory;

    public TraderMenuHolder(int page, int totalPages) {
        this.page = page;
        this.totalPages = totalPages;
    }

    public int getPage() {
        return page;
    }

    public int getTotalPages() {
        return totalPages;
    }

    public void bind(int slot, TradeProduct product) {
        slots.put(slot, product);
    }

    public TradeProduct productAt(int slot) {
        return slots.get(slot);
    }

    public void setInventory(Inventory inventory) {
        this.inventory = inventory;
    }

    @Override
    public Inventory getInventory() {
        return inventory;
    }
}
