package dev.wulnry.mosskeeper.command;

import dev.wulnry.mosskeeper.MossKeeperLTuccar;
import dev.wulnry.mosskeeper.config.ConfigManager;
import dev.wulnry.mosskeeper.config.MessageManager;
import dev.wulnry.mosskeeper.data.ProductManager;
import dev.wulnry.mosskeeper.data.TradeProduct;
import dev.wulnry.mosskeeper.gui.GuiManager;
import dev.wulnry.mosskeeper.gui.MerchantMenu;
import dev.wulnry.mosskeeper.npc.NpcManager;
import dev.wulnry.mosskeeper.util.ItemUtil;
import dev.wulnry.mosskeeper.util.TextUtil;
import org.bukkit.Location;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

import java.util.ArrayList;
import java.util.List;

/**
 * Tum yonetim komutlarinin tek giris noktasi.
 * <p>
 * Alt komutlar {@link TextUtil#normalize(String)} ile karsilastirilir; boylece
 * yonetici "takasKarşılığı", "takaskarsiligi" veya "TAKASKARŞILIĞI" yazsa da
 * komut calisir (Turkce klavye/ASCII farki sorun cikarmaz).
 */
public final class CommandManager implements CommandExecutor, TabCompleter {

    private final MossKeeperLTuccar plugin;
    private final ConfigManager configManager;
    private final MessageManager messageManager;
    private final ProductManager productManager;
    private final NpcManager npcManager;
    private final GuiManager guiManager;

    public CommandManager(MossKeeperLTuccar plugin,
                          ConfigManager configManager,
                          MessageManager messageManager,
                          ProductManager productManager,
                          NpcManager npcManager,
                          GuiManager guiManager) {
        this.plugin = plugin;
        this.configManager = configManager;
        this.messageManager = messageManager;
        this.productManager = productManager;
        this.npcManager = npcManager;
        this.guiManager = guiManager;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!sender.hasPermission("mosskeeper.admin")) {
            messageManager.send(sender, "general.no-permission");
            return true;
        }
        if (args.length == 0) {
            sendUsage(sender);
            return true;
        }

        String root = TextUtil.normalize(args[0]);
        switch (root) {
            case "urun" -> handleProduct(sender, args);
            case "takasverilecek" -> handleItem(sender, args, true);
            case "takaskarsiligi" -> handleItem(sender, args, false);
            case "takasanafiyati" -> handlePrice(sender, args, true);
            case "takasindirimfiyati" -> handlePrice(sender, args, false);
            case "npc" -> handleNpc(sender, args);
            case "liste", "list" -> handleList(sender);
            case "menu", "test" -> handleMenu(sender);
            case "yenile", "reload" -> handleReload(sender);
            default -> sendUsage(sender);
        }
        return true;
    }

    // ------------------------------------------------------------- URUN

    /** /st ürün oluştur|sil <isim> */
    private void handleProduct(CommandSender sender, String[] args) {
        if (args.length < 3) {
            sendUsage(sender);
            return;
        }
        String action = TextUtil.normalize(args[1]);
        String name = args[2];

        if (action.equals("olustur") || action.equals("ekle") || action.equals("create")) {
            if (!TextUtil.isValidName(name)) {
                messageManager.send(sender, "product.invalid-name");
                return;
            }
            if (productManager.exists(name)) {
                messageManager.send(sender, "product.already-exists", "%urun%", name);
                return;
            }
            productManager.create(name);
            persist();
            messageManager.send(sender, "product.created", "%urun%", name);
            return;
        }

        if (action.equals("sil") || action.equals("kaldir") || action.equals("delete")) {
            if (!productManager.delete(name)) {
                messageManager.send(sender, "product.not-found", "%urun%", name);
                return;
            }
            persist();
            guiManager.refreshOpenMenus();
            messageManager.send(sender, "product.deleted", "%urun%", name);
            return;
        }

        sendUsage(sender);
    }

    // ---------------------------------------------------- ESYA AYARLAMA

    /**
     * /st takasverilecek ekle <isim>  |  /st takaskarşılığı ekle <isim>
     *
     * @param give true -> odul esyasi, false -> odeme esyasi
     */
    private void handleItem(CommandSender sender, String[] args, boolean give) {
        if (!(sender instanceof Player player)) {
            messageManager.send(sender, "general.player-only");
            return;
        }
        if (args.length < 3) {
            sendUsage(sender);
            return;
        }

        TradeProduct product = productManager.get(args[2]);
        if (product == null) {
            messageManager.send(sender, "product.not-found", "%urun%", args[2]);
            return;
        }

        ItemStack inHand = player.getInventory().getItemInMainHand();
        if (inHand.getType().isAir()) {
            messageManager.send(sender, "item.hand-empty");
            return;
        }

        // Elden kopya alinir: NBT (buyu, isim, lore, CustomModelData, PDC) korunur
        // ve yoneticinin elindeki esya sonradan degisse bile profil etkilenmez.
        ItemStack copy = inHand.clone();
        boolean wasReady = product.isReady();

        if (give) {
            product.setGiveItem(copy);
        } else {
            // Odeme sablonunda miktar anlamsizdir; fiyat alanlari belirler.
            copy.setAmount(1);
            product.setCostItem(copy);
        }
        productManager.markDirty();
        persist();
        guiManager.refreshOpenMenus();

        messageManager.send(sender, give ? "product.give-set" : "product.cost-set",
                "%urun%", product.getDisplayName(),
                "%esya%", (give ? copy.getAmount() + "x " : "") + ItemUtil.displayName(copy));

        // Odeme esyasi degistiyse mevcut fiyatlar vanilla sinirini asmis olabilir.
        if (!give) {
            warnIfPriceExceedsStackLimit(sender, product, product.getMainPrice());
            warnIfPriceExceedsStackLimit(sender, product, product.getDiscountPrice());
        }
        announceStatus(sender, product, wasReady);
    }

    // -------------------------------------------------------- FIYATLAMA

    /**
     * /st takasanafiyatı ekle <miktar> <ürün_ismi>
     * /st takasindirimfiyatı ekle <miktar> <ürün_ismi>
     */
    private void handlePrice(CommandSender sender, String[] args, boolean main) {
        if (args.length < 4) {
            sendUsage(sender);
            return;
        }

        Integer parsed = TextUtil.parseInt(args[2]);
        if (parsed == null) {
            messageManager.send(sender, "general.invalid-number", "%deger%", args[2]);
            return;
        }
        int amount = parsed;
        if (amount <= 0) {
            messageManager.send(sender, "general.positive-number");
            return;
        }

        TradeProduct product = productManager.get(args[3]);
        if (product == null) {
            messageManager.send(sender, "product.not-found", "%urun%", args[3]);
            return;
        }

        boolean wasReady = product.isReady();
        if (main) {
            // Ana fiyat, indirimli fiyatin altina dusemez; aksi halde menude
            // "indirim" yerine zam gorunur (uzeri cizili deger, odenenden kucuk olur).
            if (product.getDiscountPrice() > 0 && amount < product.getDiscountPrice()) {
                messageManager.send(sender, "product.main-price-too-low",
                        "%indirim_fiyat%", String.valueOf(product.getDiscountPrice()));
                return;
            }
            product.setMainPrice(amount);
        } else {
            if (product.getMainPrice() > 0 && amount > product.getMainPrice()) {
                messageManager.send(sender, "product.discount-too-high",
                        "%ana_fiyat%", String.valueOf(product.getMainPrice()));
                return;
            }
            product.setDiscountPrice(amount);
        }
        productManager.markDirty();
        persist();
        guiManager.refreshOpenMenus();

        messageManager.send(sender, main ? "product.main-price-set" : "product.discount-price-set",
                "%urun%", product.getDisplayName(),
                "%miktar%", String.valueOf(amount));
        warnIfPriceExceedsStackLimit(sender, product, amount);
        announceStatus(sender, product, wasReady);
    }

    /**
     * Vanilla koylu ekraninin malzeme slotu tek yigin tasir. Fiyat bu siniri
     * asarsa KOYLU modunda sessizce kirpilir - yonetici bunu komut aninda
     * ogrenmeli ki yanlis fiyatla yayina cikmasin.
     */
    private void warnIfPriceExceedsStackLimit(CommandSender sender, TradeProduct product, int amount) {
        ItemStack costItem = product.getCostItem();
        if (costItem == null || amount <= 0) {
            return;
        }
        int limit = MerchantMenu.vanillaPriceLimit(costItem);
        if (amount <= limit) {
            return;
        }
        messageManager.send(sender, "product.price-exceeds-stack",
                "%miktar%", String.valueOf(amount),
                "%limit%", String.valueOf(limit),
                "%esya%", ItemUtil.displayName(costItem));
    }

    /**
     * Urun az once tamamlandiysa "aktif" bilgisi, hala eksikse eksik listesi.
     */
    private void announceStatus(CommandSender sender, TradeProduct product, boolean wasReady) {
        if (product.isReady()) {
            if (!wasReady) {
                messageManager.send(sender, "product.now-active", "%urun%", product.getDisplayName());
            }
            return;
        }
        messageManager.send(sender, "product.incomplete",
                "%urun%", product.getDisplayName(),
                "%eksikler%", String.join(", ", product.missingFields()));
    }

    // ----------------------------------------------------------------- NPC

    /** /st npc yerleştir|sil */
    private void handleNpc(CommandSender sender, String[] args) {
        if (!(sender instanceof Player player)) {
            messageManager.send(sender, "general.player-only");
            return;
        }
        if (args.length < 2) {
            sendUsage(sender);
            return;
        }

        String action = TextUtil.normalize(args[1]);
        if (action.equals("yerlestir") || action.equals("olustur") || action.equals("ekle") || action.equals("spawn")) {
            spawnNpc(sender, player);
            return;
        }

        if (action.equals("sil") || action.equals("kaldir") || action.equals("remove")) {
            if (!npcManager.removeNearest(player)) {
                messageManager.send(sender, "npc.not-found",
                        "%yaricap%", String.valueOf((int) configManager.getNpcRemoveRadius()));
                return;
            }
            messageManager.send(sender, "npc.removed");
            return;
        }

        sendUsage(sender);
    }

    private void spawnNpc(CommandSender sender, Player player) {
        Location location = player.getLocation().clone();
        switch (npcManager.spawn(location)) {
            case SUCCESS -> messageManager.send(sender, "npc.placed",
                    "%dunya%", location.getWorld().getName(),
                    "%x%", String.valueOf(location.getBlockX()),
                    "%y%", String.valueOf(location.getBlockY()),
                    "%z%", String.valueOf(location.getBlockZ()),
                    "%sayi%", String.valueOf(npcManager.size()),
                    "%limit%", String.valueOf(configManager.getNpcMaxCount()));
            case WORLD_DISABLED -> messageManager.send(sender, "npc.world-disabled");
            case LIMIT_REACHED -> messageManager.send(sender, "npc.limit-reached",
                    "%limit%", String.valueOf(configManager.getNpcMaxCount()));
            case BLOCKED -> messageManager.send(sender, "npc.spawn-blocked");
        }
    }

    // ---------------------------------------------------------- YARDIMCI

    private void handleList(CommandSender sender) {
        if (productManager.size() == 0) {
            messageManager.send(sender, "product.list-empty");
            return;
        }
        messageManager.sendRaw(sender, "product.list-header");
        for (TradeProduct product : productManager.all()) {
            messageManager.sendRaw(sender, "product.list-line",
                    "%urun%", product.getDisplayName(),
                    "%ana_fiyat%", String.valueOf(product.getMainPrice()),
                    "%indirim_fiyat%", String.valueOf(product.getDiscountPrice()),
                    "%durum%", messageManager.get(product.isReady()
                            ? "product.status-active" : "product.status-inactive"));
        }
        messageManager.sendRaw(sender, "usage.footer");
    }

    private void handleMenu(CommandSender sender) {
        if (!(sender instanceof Player player)) {
            messageManager.send(sender, "general.player-only");
            return;
        }
        guiManager.open(player, 0);
    }

    /**
     * config.yml + messages.yml + products.yml dosyalarini yeniler.
     * <p>
     * Her adimin sonucu ayri ayri izlenir; herhangi biri basarisiz olursa
     * yoneticiye <b>basarisiz</b> bildirimi gider (eski davranista hata
     * durumunda bile "basariyla yenilendi" yaziyordu).
     */
    private void handleReload(CommandSender sender) {
        long start = System.currentTimeMillis();
        // Once GUI'leri kapat: eski onbellekle acik kalan menu olmasin.
        guiManager.closeAll();

        configManager.reloadAsync(configOk -> messageManager.reloadAsync(messagesOk -> {
            guiManager.invalidate();
            npcManager.refreshLoaded();
            // Yeni auto-save araligi ancak gorev yeniden kurulunca gecerli olur.
            plugin.restartAutoSave();

            productManager.loadAsync(() -> {
                guiManager.invalidate();
                if (configOk && messagesOk) {
                    messageManager.send(sender, "general.reloaded",
                            "%sure%", String.valueOf(System.currentTimeMillis() - start));
                } else {
                    messageManager.send(sender, "general.reload-failed",
                            "%dosya%", failedFiles(configOk, messagesOk));
                }
            });
        }));
    }

    private String failedFiles(boolean configOk, boolean messagesOk) {
        if (!configOk && !messagesOk) {
            return "config.yml, messages.yml";
        }
        return configOk ? "messages.yml" : "config.yml";
    }

    private void sendUsage(CommandSender sender) {
        messageManager.sendRaw(sender, "usage.header");
        messageManager.sendList(sender, "usage.lines");
        messageManager.sendRaw(sender, "usage.footer");
    }

    /**
     * Veriyi asenkron olarak diske yazar (ana thread beklemez).
     */
    private void persist() {
        productManager.saveAsync();
    }

    // ------------------------------------------------------ TAB COMPLETE

    private static final List<String> ROOTS = List.of(
            "ürün", "takasverilecek", "takaskarşılığı", "takasanafiyatı",
            "takasindirimfiyatı", "npc", "liste", "menü", "yenile");

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        if (!sender.hasPermission("mosskeeper.admin")) {
            return List.of();
        }

        if (args.length == 1) {
            return filter(ROOTS, args[0]);
        }

        String root = TextUtil.normalize(args[0]);
        if (args.length == 2) {
            return switch (root) {
                case "urun" -> filter(List.of("oluştur", "sil"), args[1]);
                case "npc" -> filter(List.of("yerleştir", "sil"), args[1]);
                case "takasverilecek", "takaskarsiligi", "takasanafiyati", "takasindirimfiyati" ->
                        filter(List.of("ekle"), args[1]);
                default -> List.of();
            };
        }

        if (args.length == 3) {
            return switch (root) {
                case "takasverilecek", "takaskarsiligi" -> filter(productNames(), args[2]);
                case "takasanafiyati", "takasindirimfiyati" -> filter(List.of("1", "8", "16", "32", "64"), args[2]);
                case "urun" -> TextUtil.normalize(args[1]).startsWith("sil")
                        ? filter(productNames(), args[2]) : List.of();
                default -> List.of();
            };
        }

        if (args.length == 4 && (root.equals("takasanafiyati") || root.equals("takasindirimfiyati"))) {
            return filter(productNames(), args[3]);
        }
        return List.of();
    }

    private List<String> productNames() {
        List<String> names = new ArrayList<>(productManager.size());
        for (TradeProduct product : productManager.all()) {
            names.add(product.getDisplayName());
        }
        return names;
    }

    private List<String> filter(List<String> source, String prefix) {
        String normalized = TextUtil.normalize(prefix);
        if (normalized.isEmpty()) {
            return new ArrayList<>(source);
        }
        List<String> result = new ArrayList<>(source.size());
        for (String value : source) {
            if (TextUtil.normalize(value).startsWith(normalized)) {
                result.add(value);
            }
        }
        return result;
    }
}
