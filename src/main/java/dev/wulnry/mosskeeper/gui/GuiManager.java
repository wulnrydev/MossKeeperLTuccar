package dev.wulnry.mosskeeper.gui;

import dev.wulnry.mosskeeper.MossKeeperLTuccar;
import dev.wulnry.mosskeeper.config.ConfigManager;
import dev.wulnry.mosskeeper.config.MessageManager;
import dev.wulnry.mosskeeper.data.ProductManager;
import dev.wulnry.mosskeeper.data.TradeProduct;
import dev.wulnry.mosskeeper.util.ColorUtil;
import dev.wulnry.mosskeeper.util.ItemUtil;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.Sound;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemFlag;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

/**
 * Menu olusturma ve takas islemleri.
 *
 * <h3>Onbellek stratejisi</h3>
 * Urun ikonlari (lore hesabi, renk cevirisi, meta yazimi) her menu acilisinda
 * degil, yalnizca urun verisi ya da config degistiginde bir kez uretilir.
 * Menu acilisinda sadece hazir {@link ItemStack} referanslari envantere
 * yerlestirilir - bu islem O(slot) ve tahsis (allocation) uretmez.
 *
 * <h3>Acik menu takibi</h3>
 * Acik menuler {@link #openMenus} haritasinda tutulur. Boylece
 * {@code InventoryView} API'sine hic dokunulmaz; bu tip 1.21'de sinif olmaktan
 * cikip arayuze donustugu icin ondan kacinmak ileri surum uyumlulugu saglar.
 */
public final class GuiManager {

    private static final int NAV_PREVIOUS = 3;
    private static final int NAV_INFO = 4;
    private static final int NAV_NEXT = 5;
    private static final int NAV_CLOSE = 8;

    private final MossKeeperLTuccar plugin;
    private final ConfigManager configManager;
    private final MessageManager messageManager;
    private final ProductManager productManager;

    /** Menude gosterilecek (tamamlanmis) urunler - sirali. */
    private final List<TradeProduct> activeProducts = new ArrayList<>();
    /** activeProducts ile ayni sirada, hazir ikonlar. */
    private final List<ItemStack> activeIcons = new ArrayList<>();
    /** Icerik slotlari (kenar cercevesinin ici). */
    private int[] contentSlots = new int[0];

    private ItemStack fillerItem;
    private ItemStack previousItem;
    private ItemStack nextItem;
    private ItemStack closeItem;
    private ItemStack emptyItem;
    private boolean infoEnabled;

    private int cachedRevision = -1;
    private boolean layoutDirty = true;

    /** Köylü tarzi (vanilla) ekran; menu-type: KOYLU iken kullanilir. */
    private MerchantMenu merchantMenu;

    /** Acik sandik menuleri: oyuncu -> holder. */
    private final Map<UUID, TraderMenuHolder> openMenus = new HashMap<>();
    /** Takas spam korumasi. */
    private final Map<UUID, Long> lastTrade = new HashMap<>();

    public GuiManager(MossKeeperLTuccar plugin,
                      ConfigManager configManager,
                      MessageManager messageManager,
                      ProductManager productManager) {
        this.plugin = plugin;
        this.configManager = configManager;
        this.messageManager = messageManager;
        this.productManager = productManager;
    }

    public void setMerchantMenu(MerchantMenu merchantMenu) {
        this.merchantMenu = merchantMenu;
    }

    /**
     * Config yenilendiginde tum gorsel onbellegi bosaltir.
     */
    public void invalidate() {
        this.layoutDirty = true;
        this.cachedRevision = -1;
        if (merchantMenu != null) {
            merchantMenu.invalidate();
        }
    }

    // ------------------------------------------------------------ ONBELLEK

    private void rebuildIfNeeded() {
        if (layoutDirty) {
            rebuildLayout();
            layoutDirty = false;
            cachedRevision = -1;
        }
        if (cachedRevision != productManager.getRevision()) {
            rebuildProducts();
            cachedRevision = productManager.getRevision();
        }
    }

    private void rebuildLayout() {
        FileConfiguration cfg = configManager.raw();
        int rows = configManager.getGuiRows();

        // Kenar cercevesinin ici: 1..rows-2 satirlari, 1..7 sutunlari.
        List<Integer> slots = new ArrayList<>((rows - 2) * 7);
        for (int row = 1; row <= rows - 2; row++) {
            for (int column = 1; column <= 7; column++) {
                slots.add(row * 9 + column);
            }
        }
        contentSlots = new int[slots.size()];
        for (int i = 0; i < slots.size(); i++) {
            contentSlots[i] = slots.get(i);
        }

        fillerItem = cfg.getBoolean("gui.filler.enabled", true)
                ? build(material(cfg.getString("gui.filler.material"), Material.GRAY_STAINED_GLASS_PANE),
                cfg.getString("gui.filler.name", "&r"), List.of())
                : null;

        previousItem = build(material(cfg.getString("gui.navigation.previous.material"), Material.ARROW),
                cfg.getString("gui.navigation.previous.name"), cfg.getStringList("gui.navigation.previous.lore"));
        nextItem = build(material(cfg.getString("gui.navigation.next.material"), Material.ARROW),
                cfg.getString("gui.navigation.next.name"), cfg.getStringList("gui.navigation.next.lore"));
        closeItem = build(material(cfg.getString("gui.navigation.close.material"), Material.BARRIER),
                cfg.getString("gui.navigation.close.name"), cfg.getStringList("gui.navigation.close.lore"));
        emptyItem = build(material(cfg.getString("gui.empty.material"), Material.COBWEB),
                cfg.getString("gui.empty.name"), cfg.getStringList("gui.empty.lore"));
        infoEnabled = cfg.getBoolean("gui.navigation.info.enabled", true);
    }

    private void rebuildProducts() {
        activeProducts.clear();
        activeIcons.clear();

        List<String> loreTemplate = configManager.raw().getStringList("gui.product-lore");
        for (TradeProduct product : productManager.all()) {
            if (!product.isReady()) {
                continue;
            }
            activeProducts.add(product);
            activeIcons.add(createIcon(product, loreTemplate));
        }
    }

    /**
     * Urun ikonu: verilecek esyanin birebir kopyasi + takas bilgisi lore'u.
     * Orijinal esya asla degistirilmez ({@code clone}).
     */
    private ItemStack createIcon(TradeProduct product, List<String> loreTemplate) {
        ItemStack icon = product.getGiveItem().clone();
        ItemMeta meta = icon.getItemMeta();
        if (meta == null) {
            return icon;
        }

        String giveName = ItemUtil.displayName(product.getGiveItem());
        String costName = ItemUtil.displayName(product.getCostItem());

        if (!meta.hasDisplayName()) {
            meta.setDisplayName(configManager.colorize("%primary%" + giveName));
        }

        List<String> lore = meta.hasLore() ? new ArrayList<>(meta.getLore()) : new ArrayList<>();
        for (String line : loreTemplate) {
            lore.add(configManager.colorize(MessageManager.fill(line,
                    "%urun%", product.getDisplayName(),
                    "%verilecek%", product.getGiveItem().getAmount() + "x " + giveName,
                    "%karsilik%", costName,
                    "%ana_fiyat%", String.valueOf(product.getMainPrice()),
                    "%indirim_fiyat%", String.valueOf(product.getDiscountPrice()),
                    "%indirim_yuzde%", String.valueOf(product.discountPercent()),
                    "%kazanc%", String.valueOf(product.savedAmount()))));
        }
        meta.setLore(lore);
        meta.addItemFlags(ItemFlag.HIDE_ATTRIBUTES);
        icon.setItemMeta(meta);
        return icon;
    }

    private ItemStack build(Material material, String name, List<String> lore) {
        ItemStack item = new ItemStack(material);
        ItemMeta meta = item.getItemMeta();
        if (meta != null) {
            if (name != null) {
                meta.setDisplayName(configManager.colorize(name));
            }
            if (lore != null && !lore.isEmpty()) {
                List<String> colored = new ArrayList<>(lore.size());
                for (String line : lore) {
                    colored.add(configManager.colorize(line));
                }
                meta.setLore(colored);
            }
            meta.addItemFlags(ItemFlag.HIDE_ATTRIBUTES, ItemFlag.HIDE_ENCHANTS);
            item.setItemMeta(meta);
        }
        return item;
    }

    private Material material(String name, Material fallback) {
        if (name == null || name.isEmpty()) {
            return fallback;
        }
        Material material = Material.matchMaterial(name.toUpperCase(Locale.ENGLISH));
        return material != null ? material : fallback;
    }

    // ---------------------------------------------------------------- ACMA

    /**
     * Tuccar arayuzunu acar. Hangi arayuzun acilacagini {@code gui.menu-type}
     * belirler: KOYLU -> vanilla ticaret ekrani, SANDIK -> asagidaki menu.
     */
    public void open(Player player, int page) {
        if (configManager.getMenuType() == ConfigManager.MenuType.KOYLU && merchantMenu != null) {
            merchantMenu.open(player);
            return;
        }
        // "Menu aciliyor" bildirimi yalnizca ilk aciliszta gonderilir;
        // sayfa gecislerinde tekrar etmemesi icin openChestMenu'de degil burada.
        messageManager.send(player, "trade.menu-opening");
        openChestMenu(player, page);
    }

    /**
     * Menuyu bir sonraki tick'te acar.
     * <p>
     * Envanter olayi <b>islenirken</b> yeni envanter acmak Bukkit'te desteklenmez
     * (imlecteki esya ve istemci durumu bozulabilir). Tiklamadan tetiklenen tum
     * acilislar bu yoldan gecer.
     */
    private void openLater(Player player, int page) {
        Bukkit.getScheduler().runTask(plugin, () -> {
            if (player.isOnline()) {
                openChestMenu(player, page);
            }
        });
    }

    private void openChestMenu(Player player, int page) {
        if (!productManager.isLoaded()) {
            messageManager.send(player, "general.data-loading");
            return;
        }
        rebuildIfNeeded();

        int perPage = Math.max(1, contentSlots.length);
        int totalPages = Math.max(1, (int) Math.ceil(activeProducts.size() / (double) perPage));
        int currentPage = Math.max(0, Math.min(page, totalPages - 1));

        TraderMenuHolder holder = new TraderMenuHolder(currentPage, totalPages);
        Inventory inventory = Bukkit.createInventory(holder, configManager.getGuiSize(),
                ColorUtil.color(configManager.applyPalette(configManager.getGuiTitle())));
        holder.setInventory(inventory);

        // Cerceve
        if (fillerItem != null) {
            for (int slot = 0; slot < inventory.getSize(); slot++) {
                inventory.setItem(slot, fillerItem);
            }
            for (int slot : contentSlots) {
                inventory.setItem(slot, null);
            }
        }

        // Urunler
        if (activeProducts.isEmpty()) {
            inventory.setItem(contentSlots[contentSlots.length / 2], emptyItem);
        } else {
            int start = currentPage * perPage;
            int end = Math.min(activeProducts.size(), start + perPage);
            for (int index = start; index < end; index++) {
                int slot = contentSlots[index - start];
                inventory.setItem(slot, activeIcons.get(index));
                holder.bind(slot, activeProducts.get(index));
            }
        }

        // Navigasyon
        int base = (configManager.getGuiRows() - 1) * 9;
        inventory.setItem(base + NAV_CLOSE, closeItem);
        if (currentPage > 0) {
            inventory.setItem(base + NAV_PREVIOUS, withPageLore(previousItem, "%onceki%", currentPage, totalPages));
        }
        if (currentPage < totalPages - 1) {
            inventory.setItem(base + NAV_NEXT, withPageLore(nextItem, "%sonraki%", currentPage + 2, totalPages));
        }
        if (infoEnabled) {
            inventory.setItem(base + NAV_INFO, createInfoItem(player));
        }

        // Sirasi onemli: openInventory once ONCEKI envanteri kapatir ve bu da
        // InventoryCloseEvent -> forgetMenu zincirini tetikler. Kaydi sonra
        // koymazsak yeni menuyu kendi kapanma olayimiz silerdi.
        player.openInventory(inventory);
        openMenus.put(player.getUniqueId(), holder);
        playSound(player, configManager.getSoundOpen(), 0.6F, 1.4F);
    }

    private ItemStack withPageLore(ItemStack template, String placeholder, int pageNumber, int totalPages) {
        ItemStack item = template.clone();
        ItemMeta meta = item.getItemMeta();
        if (meta != null && meta.hasLore()) {
            List<String> lore = new ArrayList<>(meta.getLore());
            lore.replaceAll(line -> MessageManager.fill(line,
                    placeholder, String.valueOf(pageNumber),
                    "%toplam%", String.valueOf(totalPages)));
            meta.setLore(lore);
            item.setItemMeta(meta);
        }
        return item;
    }

    private ItemStack createInfoItem(Player player) {
        FileConfiguration cfg = configManager.raw();
        List<String> lore = new ArrayList<>();
        for (String line : cfg.getStringList("gui.navigation.info.lore")) {
            lore.add(configManager.colorize(MessageManager.fill(line,
                    "%oyuncu%", player.getName(),
                    "%urun_sayisi%", String.valueOf(activeProducts.size()))));
        }
        ItemStack item = new ItemStack(material(cfg.getString("gui.navigation.info.material"), Material.EMERALD));
        ItemMeta meta = item.getItemMeta();
        if (meta != null) {
            meta.setDisplayName(configManager.colorize(cfg.getString("gui.navigation.info.name", "&#15FF08Şanslı Tüccar")));
            meta.setLore(lore);
            meta.addItemFlags(ItemFlag.values());
            item.setItemMeta(meta);
        }
        return item;
    }

    // ------------------------------------------------------------ TIKLAMA

    /**
     * Menu tiklamalarini isler. Cagiran listener event'i zaten iptal etmistir.
     */
    public void handleClick(Player player, TraderMenuHolder holder, int slot, boolean shiftClick) {
        int base = (configManager.getGuiRows() - 1) * 9;

        if (slot == base + NAV_CLOSE) {
            player.closeInventory();
            return;
        }
        if (slot == base + NAV_PREVIOUS && holder.getPage() > 0) {
            openLater(player, holder.getPage() - 1);
            return;
        }
        if (slot == base + NAV_NEXT && holder.getPage() < holder.getTotalPages() - 1) {
            openLater(player, holder.getPage() + 1);
            return;
        }

        TradeProduct product = holder.productAt(slot);
        if (product == null) {
            return;
        }
        trade(player, product, shiftClick);
    }

    // -------------------------------------------------------------- TAKAS

    private void trade(Player player, TradeProduct product, boolean bulk) {
        // Spam korumasi: sayac, basarisiz denemeler dahil HER girisimde islenir.
        // Aksi halde "envanterin dolu" gibi bir hataya takilan oyuncu, hicbir
        // bekleme yemeden pahali hesabi sinirsiz tekrarlatabilirdi.
        long now = System.currentTimeMillis();
        long cooldown = configManager.getTradeCooldownMillis();
        if (cooldown > 0) {
            Long last = lastTrade.get(player.getUniqueId());
            if (last != null && now - last < cooldown) {
                messageManager.send(player, "trade.cooldown");
                return;
            }
            lastTrade.put(player.getUniqueId(), now);
        }

        // Urun, menu acikken silinmis/bozulmus olabilir.
        TradeProduct current = productManager.get(product.getId());
        if (current == null || !current.isReady()) {
            messageManager.send(player, "trade.no-products");
            playSound(player, configManager.getSoundFail(), 0.8F, 1.0F);
            openLater(player, 0);
            return;
        }

        ItemStack costTemplate = current.getCostItem();
        ItemStack giveTemplate = current.getGiveItem();
        int price = current.getDiscountPrice();

        int owned = ItemUtil.count(player.getInventory(), costTemplate);
        if (owned < price) {
            messageManager.send(player, "trade.not-enough",
                    "%gerekli%", String.valueOf(price),
                    "%mevcut%", String.valueOf(owned),
                    "%esya%", ItemUtil.displayName(costTemplate));
            playSound(player, configManager.getSoundFail(), 0.8F, 1.0F);
            return;
        }

        // Toplu takas adedi tek hesapla bulunur: hem odeyebildigi hem de
        // envanterine sigdigi kadar. (Eskiden birer birer azaltan bir dongu
        // vardi; dolu envanterde binlerce tur donup ana thread'i kilitliyordu.)
        // Envanter yalnizca BIR kez taranir.
        int giveAmount = Math.max(1, giveTemplate.getAmount());
        int capacity = ItemUtil.capacityFor(player.getInventory(), giveTemplate);
        int times = bulk
                ? Math.max(1, Math.min(owned / price, capacity / giveAmount))
                : 1;

        int totalGive = giveAmount * times;
        int totalCost = price * times;

        if (capacity < totalGive) {
            messageManager.send(player, "trade.inventory-full");
            playSound(player, configManager.getSoundFail(), 0.8F, 1.0F);
            return;
        }

        // Once odemeyi al: eksik kalirsa hicbir sey verme (dupe korumasi).
        int notRemoved = ItemUtil.remove(player.getInventory(), costTemplate, totalCost);
        if (notRemoved > 0) {
            messageManager.send(player, "trade.not-enough",
                    "%gerekli%", String.valueOf(totalCost),
                    "%mevcut%", String.valueOf(totalCost - notRemoved),
                    "%esya%", ItemUtil.displayName(costTemplate));
            playSound(player, configManager.getSoundFail(), 0.8F, 1.0F);
            return;
        }

        // Sonra odulu ver; olasi artik (yaris durumu) yere birakilir.
        Map<Integer, ItemStack> leftover = player.getInventory()
                .addItem(ItemUtil.split(giveTemplate, totalGive).toArray(new ItemStack[0]));
        if (!leftover.isEmpty()) {
            for (ItemStack stack : leftover.values()) {
                player.getWorld().dropItemNaturally(player.getLocation(), stack);
            }
            messageManager.send(player, "trade.dropped");
        }
        // Menu acikken envanteri disaridan degistirdik; istemci gorunumu tazelensin.
        player.updateInventory();

        messageManager.sendTradeResult(player,
                ItemUtil.displayName(giveTemplate), giveAmount, times, totalCost,
                ItemUtil.displayName(costTemplate));
        playSound(player, configManager.getSoundSuccess(), 0.7F, 1.6F);
    }

    // ------------------------------------------------------------- YARDIMCI

    private void playSound(Player player, Sound sound, float volume, float pitch) {
        if (!configManager.isSoundsEnabled() || sound == null) {
            return;
        }
        player.playSound(player.getLocation(), sound, volume, pitch);
    }

    /**
     * Acik menuleri kapatir (reload/disable sirasinda hayalet menu kalmasin).
     */
    public void closeAll() {
        for (UUID playerId : new ArrayList<>(openMenus.keySet())) {
            Player player = Bukkit.getPlayer(playerId);
            if (player != null) {
                player.closeInventory();
            }
        }
        openMenus.clear();
        if (merchantMenu != null) {
            merchantMenu.closeAll();
        }
    }

    /**
     * Urun verisi degistiginde acik menuleri aninda tazeler.
     * <p>
     * Köylü modunda acik ekran, eski tariflerle kalmasin diye kapatilir;
     * oyuncu NPC'ye tekrar tikladiginda guncel liste ile acilir.
     */
    public void refreshOpenMenus() {
        if (configManager.getMenuType() == ConfigManager.MenuType.KOYLU) {
            if (merchantMenu != null) {
                merchantMenu.closeAll();
            }
            return;
        }
        for (Map.Entry<UUID, TraderMenuHolder> entry : new ArrayList<>(openMenus.entrySet())) {
            Player player = Bukkit.getPlayer(entry.getKey());
            if (player != null) {
                openChestMenu(player, entry.getValue().getPage());
            }
        }
    }

    /** Menu kapandiginda / oyuncu ciktiginda takip kaydini temizler. */
    public void forget(UUID playerId) {
        openMenus.remove(playerId);
        lastTrade.remove(playerId);
    }

    /** Yalnizca menu takibini birakir; cooldown korunur (menu kapatma icin). */
    public void forgetMenu(UUID playerId) {
        openMenus.remove(playerId);
    }
}
