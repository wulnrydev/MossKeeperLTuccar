package dev.wulnry.mosskeeper.gui;

import dev.wulnry.mosskeeper.config.ConfigManager;
import dev.wulnry.mosskeeper.config.MessageManager;
import dev.wulnry.mosskeeper.data.ProductManager;
import dev.wulnry.mosskeeper.data.TradeProduct;
import dev.wulnry.mosskeeper.util.ColorUtil;
import dev.wulnry.mosskeeper.util.ItemUtil;
import org.bukkit.Bukkit;
import org.bukkit.Sound;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.Merchant;
import org.bukkit.inventory.MerchantRecipe;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * <b>Köylü tarzı</b> takas ekrani (vanilla Merchant UI).
 *
 * <h3>Neden "special price"?</h3>
 * Vanilla ticaret ekrani, bir teklifin taban fiyati ile guncel fiyati farkli
 * oldugunda taban fiyati <b>uzeri cizili</b> kucuk bir ikon olarak, indirimli
 * fiyati ise buyuk ikon olarak cizer. Biz de:
 * <pre>
 *   malzeme miktari = ANA FIYAT          (uzeri cizili gorunen)
 *   special price   = indirim - ana      (negatif fark)
 *   sonuc fiyat     = INDIRIMLI FIYAT    (oyuncunun odedigi)
 * </pre>
 * Boylece indirim efekti istemcinin kendi arayuzunde, hicbir paket
 * manipulasyonu ya da sahte esya olmadan gorunur.
 *
 * <h3>Neden lore eklenmiyor?</h3>
 * Vanilla ekranda gosterilen sonuc esyasi, oyuncunun <b>fiilen aldigi</b>
 * esyanin ta kendisidir. Ona aciklama lore'u eklemek, lore'u kalici olarak
 * oyuncunun envanterine tasirdi. Indirim bilgisi zaten uzeri cizili fiyatla
 * gosterildigi icin burada lore kullanilmaz; ozel lore isteyen yoneticiler
 * {@code gui.menu-type: SANDIK} moduna gecmelidir.
 *
 * <h3>Sanal tuccar</h3>
 * Gercek koylu entity'sinin takas listesi <b>hic</b> kullanilmaz; her oyuncuya
 * ozel, gecici bir {@link Merchant} acilir. Bu sayede oyuncular NPC'yi
 * seviye atlatamaz, stok tuketemez ve vanilla "restock" mantigi devreye girmez.
 */
public final class MerchantMenu {

    /** Vanilla ticaret ekranindaki sonuc (odul) slotu. */
    public static final int RESULT_SLOT = 2;
    /** Pratik olarak tukenmeyen stok. */
    private static final int MAX_USES = 1_000_000;

    private final ConfigManager configManager;
    private final MessageManager messageManager;
    private final ProductManager productManager;

    /** Acik olan sanal tuccar oturumlari. */
    private final Map<UUID, Session> sessions = new HashMap<>();

    /** Tarif onbellegi - yalnizca urun verisi degistiginde yeniden uretilir. */
    private final List<MerchantRecipe> cachedRecipes = new ArrayList<>();
    private final List<TradeProduct> cachedProducts = new ArrayList<>();
    /** Vanilla siniri uygulandiktan sonra oyuncunun gercekten odedigi fiyatlar. */
    private final List<Integer> cachedEffectiveCosts = new ArrayList<>();
    private int cachedRevision = -1;

    public MerchantMenu(ConfigManager configManager,
                        MessageManager messageManager,
                        ProductManager productManager) {
        this.configManager = configManager;
        this.messageManager = messageManager;
        this.productManager = productManager;
    }

    public void invalidate() {
        this.cachedRevision = -1;
    }

    // ---------------------------------------------------------------- ACMA

    public void open(Player player) {
        if (!productManager.isLoaded()) {
            messageManager.send(player, "general.data-loading");
            return;
        }

        rebuildIfNeeded();

        if (cachedRecipes.isEmpty()) {
            messageManager.send(player, "trade.no-products");
            playSound(player, configManager.getSoundFail(), 0.8F, 1.0F);
            return;
        }

        Merchant merchant = Bukkit.createMerchant(
                ColorUtil.color(configManager.applyPalette(configManager.getGuiTitle())));
        merchant.setRecipes(new ArrayList<>(cachedRecipes));

        if (player.openMerchant(merchant, true) == null) {
            return;
        }

        sessions.put(player.getUniqueId(),
                new Session(merchant, new ArrayList<>(cachedProducts), new ArrayList<>(cachedEffectiveCosts)));
        messageManager.send(player, "trade.menu-opening");
        playSound(player, configManager.getSoundOpen(), 0.6F, 1.4F);
    }

    private void rebuildIfNeeded() {
        if (cachedRevision == productManager.getRevision()) {
            return;
        }
        cachedRecipes.clear();
        cachedProducts.clear();
        cachedEffectiveCosts.clear();

        for (TradeProduct product : productManager.all()) {
            if (!product.isReady()) {
                continue;
            }
            int maxStack = Math.max(1, product.getCostItem().getMaxStackSize());
            int mainPrice = Math.min(product.getMainPrice(), maxStack);
            int discountPrice = Math.min(product.getDiscountPrice(), maxStack);

            cachedRecipes.add(createRecipe(product, mainPrice, discountPrice));
            cachedProducts.add(product);
            cachedEffectiveCosts.add(discountPrice);
        }
        cachedRevision = productManager.getRevision();
    }

    /**
     * Bir takas profilini vanilla teklife cevirir.
     *
     * @param mainPrice     vanilla sinirina kirpilmis ana fiyat (uzeri cizili)
     * @param discountPrice vanilla sinirina kirpilmis indirimli fiyat (odenen)
     */
    private MerchantRecipe createRecipe(TradeProduct product, int mainPrice, int discountPrice) {
        ItemStack cost = product.getCostItem().clone();
        cost.setAmount(mainPrice);

        MerchantRecipe recipe = new MerchantRecipe(product.getGiveItem().clone(), 0, MAX_USES, false);
        recipe.addIngredient(cost);

        // Fiyat yalnizca bizim belirledigimiz indirime gore degissin.
        recipe.setPriceMultiplier(0.0F);
        recipe.setDemand(0);
        recipe.setVillagerExperience(0);
        recipe.setExperienceReward(false);
        // Negatif fark -> ana fiyat uzeri cizili, indirimli fiyat aktif.
        recipe.setSpecialPrice(discountPrice - mainPrice);
        return recipe;
    }

    /**
     * Bir fiyatin vanilla koylu ekraninda kirpilip kirpilmayacagi.
     * Yonetici komutu girerken uyarilabilsin diye disari acilir.
     */
    public static int vanillaPriceLimit(ItemStack costItem) {
        return costItem == null ? 64 : Math.max(1, costItem.getMaxStackSize());
    }

    // ------------------------------------------------------------- OTURUM

    public Session session(Player player) {
        return sessions.get(player.getUniqueId());
    }

    public void closeSession(UUID playerId) {
        sessions.remove(playerId);
    }

    public void closeAll() {
        if (sessions.isEmpty()) {
            return;
        }
        for (UUID playerId : new ArrayList<>(sessions.keySet())) {
            Player player = Bukkit.getPlayer(playerId);
            if (player != null) {
                player.closeInventory();
            }
        }
        sessions.clear();
    }

    // -------------------------------------------------------------- TAKAS

    /**
     * Vanilla takasi tamamlandiktan sonra bilgilendirme yapar.
     * Esya alisverisini istemci/sunucu vanilla mantigi yurutur; burada
     * yalnizca mesaj ve ses vardir (dupe riski tasiyan ek islem yok).
     *
     * @param unitCost   tek takasta gercekten odenen miktar (kirpilmis fiyat)
     * @param tradeCount kac takas yapildigi
     */
    public void announceTrade(Player player, TradeProduct product, int unitCost, int tradeCount) {
        ItemStack give = product.getGiveItem();
        messageManager.sendTradeResult(player,
                ItemUtil.displayName(give),
                give.getAmount(),
                tradeCount,
                unitCost * tradeCount,
                ItemUtil.displayName(product.getCostItem()));
        playSound(player, configManager.getSoundSuccess(), 0.7F, 1.6F);
    }

    private void playSound(Player player, Sound sound, float volume, float pitch) {
        if (!configManager.isSoundsEnabled() || sound == null) {
            return;
        }
        player.playSound(player.getLocation(), sound, volume, pitch);
    }

    /**
     * Bir oyuncunun acik sanal tuccar oturumu.
     */
    public static final class Session {

        private final Merchant merchant;
        private final List<TradeProduct> products;
        private final List<Integer> effectiveCosts;

        Session(Merchant merchant, List<TradeProduct> products, List<Integer> effectiveCosts) {
            this.merchant = merchant;
            this.products = products;
            this.effectiveCosts = effectiveCosts;
        }

        public Merchant getMerchant() {
            return merchant;
        }

        public TradeProduct productAt(int index) {
            return index >= 0 && index < products.size() ? products.get(index) : null;
        }

        /**
         * @return teklifte gercekten odenen birim fiyat; gecersiz indekste -1
         */
        public int effectiveCostAt(int index) {
            return index >= 0 && index < effectiveCosts.size() ? effectiveCosts.get(index) : -1;
        }

        /**
         * @return teklifin o ana kadarki kullanim sayisi (takas adedi)
         */
        public int usesOf(int index) {
            if (index < 0 || index >= merchant.getRecipeCount()) {
                return -1;
            }
            return merchant.getRecipe(index).getUses();
        }
    }
}
