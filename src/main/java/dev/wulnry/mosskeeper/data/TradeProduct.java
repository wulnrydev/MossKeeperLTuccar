package dev.wulnry.mosskeeper.data;

import dev.wulnry.mosskeeper.util.ItemUtil;
import dev.wulnry.mosskeeper.util.TextUtil;
import org.bukkit.inventory.ItemStack;

import java.util.ArrayList;
import java.util.List;

/**
 * Tek bir takas profili (urun).
 * <p>
 * {@code id} normalize edilmis (ASCII, kucuk harf) benzersiz anahtardir;
 * {@code displayName} ise yoneticinin yazdigi orijinal isimdir.
 *
 * <h3>Serilestirme onbellegi</h3>
 * Base64 kodlamasi pahalidir ve her kayitta tum urunler icin tekrar edilirse
 * ana thread'de gereksiz yuk olusur. Kodlanmis metin esya degismedigi surece
 * saklanir; {@code setGiveItem}/{@code setCostItem} onbellegi gecersiz kilar.
 */
public final class TradeProduct {

    private final String id;
    private final String displayName;

    /** Takas sonucunda oyuncuya VERILECEK esya (NBT dahil). */
    private ItemStack giveItem;
    /** Takas icin oyuncudan ISTENECEK esya turu (NBT dahil). */
    private ItemStack costItem;
    /** Menude uzeri cizili gosterilen indirimsiz fiyat. */
    private int mainPrice;
    /** Oyuncunun fiilen odeyecegi fiyat. */
    private int discountPrice;

    private String giveItemData;
    private String costItemData;

    public TradeProduct(String displayName) {
        this(TextUtil.normalize(displayName), displayName);
    }

    public TradeProduct(String id, String displayName) {
        this.id = id;
        this.displayName = displayName;
    }

    public String getId() {
        return id;
    }

    public String getDisplayName() {
        return displayName;
    }

    public ItemStack getGiveItem() {
        return giveItem;
    }

    public void setGiveItem(ItemStack giveItem) {
        this.giveItem = giveItem;
        this.giveItemData = null;
    }

    public ItemStack getCostItem() {
        return costItem;
    }

    public void setCostItem(ItemStack costItem) {
        this.costItem = costItem;
        this.costItemData = null;
    }

    /**
     * Diskten okunan esyayi, kodlanmis hali zaten bilindigi icin yeniden
     * kodlamadan yerine koyar.
     */
    void restoreGiveItem(ItemStack item, String encoded) {
        this.giveItem = item;
        this.giveItemData = item != null ? encoded : null;
    }

    void restoreCostItem(ItemStack item, String encoded) {
        this.costItem = item;
        this.costItemData = item != null ? encoded : null;
    }

    /**
     * @return kaydedilecek Base64 metni; esya yoksa {@code null}
     */
    String getGiveItemData() {
        if (giveItem == null) {
            return null;
        }
        if (giveItemData == null) {
            giveItemData = ItemUtil.encode(giveItem);
        }
        return giveItemData;
    }

    String getCostItemData() {
        if (costItem == null) {
            return null;
        }
        if (costItemData == null) {
            costItemData = ItemUtil.encode(costItem);
        }
        return costItemData;
    }

    public int getMainPrice() {
        return mainPrice;
    }

    public void setMainPrice(int mainPrice) {
        this.mainPrice = mainPrice;
    }

    public int getDiscountPrice() {
        return discountPrice;
    }

    public void setDiscountPrice(int discountPrice) {
        this.discountPrice = discountPrice;
    }

    /**
     * Menude gosterilebilmesi icin gereken tum alanlar dolduruldu mu?
     */
    public boolean isReady() {
        return giveItem != null && costItem != null && mainPrice > 0 && discountPrice > 0;
    }

    /**
     * Eksik alanlarin yoneticiye gosterilecek listesi.
     */
    public List<String> missingFields() {
        List<String> missing = new ArrayList<>(4);
        if (giveItem == null) {
            missing.add("takasverilecek");
        }
        if (costItem == null) {
            missing.add("takaskarşılığı");
        }
        if (mainPrice <= 0) {
            missing.add("takasanafiyatı");
        }
        if (discountPrice <= 0) {
            missing.add("takasindirimfiyatı");
        }
        return missing;
    }

    /**
     * Indirim yuzdesi (0-100). Ana fiyat yoksa 0 doner.
     */
    public int discountPercent() {
        if (mainPrice <= 0 || discountPrice <= 0 || discountPrice >= mainPrice) {
            return 0;
        }
        return (int) Math.round(((mainPrice - discountPrice) * 100.0D) / mainPrice);
    }

    /**
     * Oyuncunun takas basina cebinde kalan miktar.
     */
    public int savedAmount() {
        return Math.max(0, mainPrice - discountPrice);
    }
}
