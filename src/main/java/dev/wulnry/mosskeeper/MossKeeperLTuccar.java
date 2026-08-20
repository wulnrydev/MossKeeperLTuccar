package dev.wulnry.mosskeeper;

import dev.wulnry.mosskeeper.command.CommandManager;
import dev.wulnry.mosskeeper.config.ConfigManager;
import dev.wulnry.mosskeeper.config.MessageManager;
import dev.wulnry.mosskeeper.data.ProductManager;
import dev.wulnry.mosskeeper.gui.GuiManager;
import dev.wulnry.mosskeeper.gui.MerchantMenu;
import dev.wulnry.mosskeeper.listener.GuiListener;
import dev.wulnry.mosskeeper.listener.MerchantListener;
import dev.wulnry.mosskeeper.listener.NpcListener;
import dev.wulnry.mosskeeper.npc.NpcManager;
import org.bukkit.command.PluginCommand;
import org.bukkit.plugin.PluginManager;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitRunnable;
import org.bukkit.scheduler.BukkitTask;

/**
 * MossKeeperLTuccar - BoxMining "Sansli Tuccar" sistemi.
 *
 * @author wulnrydev
 */
public final class MossKeeperLTuccar extends JavaPlugin {

    private ConfigManager configManager;
    private MessageManager messageManager;
    private ProductManager productManager;
    private NpcManager npcManager;
    private GuiManager guiManager;
    private MerchantMenu merchantMenu;

    private BukkitTask autoSaveTask;

    @Override
    public void onEnable() {
        long start = System.currentTimeMillis();

        // 1) Yapilandirma (acilista, oyuncu yokken; tek seferlik senkron okuma)
        this.configManager = new ConfigManager(this);
        this.configManager.ensureDataFolder();
        this.configManager.load();

        this.messageManager = new MessageManager(this, configManager);
        this.messageManager.load();

        // 2) Servisler
        this.productManager = new ProductManager(this);
        this.npcManager = new NpcManager(this, configManager);
        this.guiManager = new GuiManager(this, configManager, messageManager, productManager);
        this.merchantMenu = new MerchantMenu(configManager, messageManager, productManager);
        this.guiManager.setMerchantMenu(merchantMenu);

        // 3) Veri: disk okuma asenkron, uygulama ana thread'de
        this.productManager.loadAsync(null);
        this.npcManager.loadAsync(() -> npcManager.refreshLoadedChunks());

        // 4) Komut & olaylar
        registerCommand();
        PluginManager pluginManager = getServer().getPluginManager();
        pluginManager.registerEvents(new GuiListener(guiManager), this);
        pluginManager.registerEvents(new MerchantListener(this, merchantMenu), this);
        pluginManager.registerEvents(new NpcListener(npcManager, guiManager, configManager), this);

        // 5) Otomatik kayit - yalnizca degisiklik varsa diske yazar
        restartAutoSave();

        getLogger().info("MossKeeperLTuccar v" + getDescription().getVersion()
                + " etkinlestirildi. (" + (System.currentTimeMillis() - start) + "ms)");
    }

    @Override
    public void onDisable() {
        if (autoSaveTask != null) {
            autoSaveTask.cancel();
            autoSaveTask = null;
        }
        if (guiManager != null) {
            guiManager.closeAll();
        }
        // onDisable'da scheduler kapali oldugundan asenkron gorev calistirilamaz;
        // veri kaybini onlemek icin bu tek noktada senkron yazariz.
        if (productManager != null) {
            productManager.saveNow();
        }
        if (npcManager != null) {
            npcManager.saveNow();
        }
        getLogger().info("MossKeeperLTuccar devre disi birakildi.");
    }

    private void registerCommand() {
        CommandManager commandManager = new CommandManager(
                this, configManager, messageManager, productManager, npcManager, guiManager);

        PluginCommand command = getCommand("şanslıtüccar");
        if (command == null) {
            // Sunucu plugin.yml'i UTF-8 disi bir karakter setiyle okuduysa
            // Turkce komut adi kaybolabilir; ASCII takma ad ile devam ederiz.
            command = getCommand("sanslituccar");
        }
        if (command == null) {
            getLogger().severe("Ana komut kaydedilemedi! plugin.yml UTF-8 olarak kaydedilmis mi?");
            return;
        }
        command.setExecutor(commandManager);
        command.setTabCompleter(commandManager);
    }

    /**
     * Otomatik kayit gorevini (yeniden) kurar.
     * <p>
     * {@code /şanslıtüccar yenile} sonrasi da cagrilir; aksi halde config'de
     * degistirilen {@code auto-save-minutes} degeri sunucu yeniden baslayana
     * kadar etkisiz kalirdi.
     */
    public void restartAutoSave() {
        if (autoSaveTask != null) {
            autoSaveTask.cancel();
        }
        long period = configManager.getAutoSaveTicks();
        this.autoSaveTask = new BukkitRunnable() {
            @Override
            public void run() {
                if (productManager.isDirty()) {
                    productManager.saveAsync();
                }
                if (npcManager.isDirty()) {
                    npcManager.saveAsync();
                }
            }
        }.runTaskTimer(this, period, period);
    }

    public ConfigManager getConfigManager() {
        return configManager;
    }

    public MessageManager getMessageManager() {
        return messageManager;
    }

    public ProductManager getProductManager() {
        return productManager;
    }

    public NpcManager getNpcManager() {
        return npcManager;
    }

    public GuiManager getGuiManager() {
        return guiManager;
    }

    public MerchantMenu getMerchantMenu() {
        return merchantMenu;
    }
}
