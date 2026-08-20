package dev.wulnry.mosskeeper.listener;

import dev.wulnry.mosskeeper.config.ConfigManager;
import dev.wulnry.mosskeeper.gui.GuiManager;
import dev.wulnry.mosskeeper.npc.NpcManager;
import org.bukkit.Sound;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityCombustEvent;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.entity.EntityTargetEvent;
import org.bukkit.event.entity.EntityTransformEvent;
import org.bukkit.event.entity.VillagerAcquireTradeEvent;
import org.bukkit.event.entity.VillagerCareerChangeEvent;
import org.bukkit.event.player.PlayerInteractAtEntityEvent;
import org.bukkit.event.player.PlayerInteractEntityEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.world.ChunkLoadEvent;
import org.bukkit.event.world.EntitiesLoadEvent;
import org.bukkit.event.world.WorldLoadEvent;
import org.bukkit.inventory.EquipmentSlot;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Tuccar NPC'sinin etkilesim ve koruma katmani.
 */
public final class NpcListener implements Listener {

    private final NpcManager npcManager;
    private final GuiManager guiManager;
    private final ConfigManager configManager;

    /**
     * Istemci tek sag tikta hem INTERACT hem INTERACT_AT paketi gonderebilir;
     * bu koruma menunun iki kez acilmasini engeller.
     */
    private final Map<UUID, Long> lastOpen = new HashMap<>();

    public NpcListener(NpcManager npcManager, GuiManager guiManager, ConfigManager configManager) {
        this.npcManager = npcManager;
        this.guiManager = guiManager;
        this.configManager = configManager;
    }

    // ---------------------------------------------------------- ETKILESIM

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onInteract(PlayerInteractEntityEvent event) {
        handleInteract(event.getPlayer(), event.getRightClicked(), event.getHand(), event);
    }

    /**
     * Bazi surumlerde bu olay ayri bir handler listesi kullanir; vanilla
     * koylu menusunun acilmamasi icin burada da iptal edilir.
     */
    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onInteractAt(PlayerInteractAtEntityEvent event) {
        handleInteract(event.getPlayer(), event.getRightClicked(), event.getHand(), event);
    }

    private void handleInteract(Player player, Entity entity, EquipmentSlot hand,
                                org.bukkit.event.Cancellable cancellable) {
        if (!npcManager.isTrader(entity)) {
            return;
        }

        // Vanilla ticaret menusu KESINLIKLE acilmamali.
        cancellable.setCancelled(true);

        if (hand != EquipmentSlot.HAND) {
            return;
        }
        if (!player.hasPermission("mosskeeper.use")) {
            return;
        }

        long guard = configManager.getNpcInteractCooldownMillis();
        if (guard > 0) {
            long now = System.currentTimeMillis();
            Long last = lastOpen.get(player.getUniqueId());
            if (last != null && now - last < guard) {
                return;
            }
            lastOpen.put(player.getUniqueId(), now);
        }

        Sound sound = configManager.getNpcInteractSound();
        if (configManager.isSoundsEnabled() && sound != null) {
            player.playSound(entity.getLocation(), sound, 0.7F, 1.1F);
        }
        guiManager.open(player, 0);
    }

    // ------------------------------------------------------------ KORUMA

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onDamage(EntityDamageEvent event) {
        if (npcManager.isTrader(event.getEntity())) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onCombust(EntityCombustEvent event) {
        if (npcManager.isTrader(event.getEntity())) {
            event.setCancelled(true);
        }
    }

    /** Zombi vb. tarafindan hedef alinmasin (gereksiz AI hesabi da olusmaz). */
    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onTarget(EntityTargetEvent event) {
        if (npcManager.isTrader(event.getTarget())) {
            event.setCancelled(true);
        }
    }

    /** Zombi koyluye donusum engellenir. */
    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onTransform(EntityTransformEvent event) {
        if (npcManager.isTrader(event.getEntity())) {
            event.setCancelled(true);
        }
    }

    /** Vanilla takas listesi hic olusturulmasin. */
    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onAcquireTrade(VillagerAcquireTradeEvent event) {
        if (npcManager.isTrader(event.getEntity())) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onCareerChange(VillagerCareerChangeEvent event) {
        if (npcManager.isTrader(event.getEntity())) {
            event.setCancelled(true);
        }
    }

    // ---------------------------------------------------------- KALICILIK

    /**
     * Asil dogrulama noktasi: entity deposunun yuklendigi <b>kesin</b> andir.
     * Paper 1.17+ entity'leri chunk'tan ayri yukledigi icin
     * {@code ChunkLoadEvent} tek basina guvenilir degildir.
     */
    @EventHandler(priority = EventPriority.MONITOR)
    public void onEntitiesLoad(EntitiesLoadEvent event) {
        npcManager.handleEntitiesLoaded(event.getChunk(), event.getEntities());
    }

    /**
     * {@code EntitiesLoadEvent} yayinlamayan sunucu yazilimlari icin yedek yol.
     * Entity deposu hazir degilse NpcManager hicbir sey yapmaz.
     */
    @EventHandler(priority = EventPriority.MONITOR)
    public void onChunkLoad(ChunkLoadEvent event) {
        npcManager.handleChunkLoad(event.getChunk());
    }

    /** Dunya bizden sonra yuklendiyse (Multiverse vb.) kayitlari dogrula. */
    @EventHandler(priority = EventPriority.MONITOR)
    public void onWorldLoad(WorldLoadEvent event) {
        npcManager.handleWorldLoad(event.getWorld());
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        lastOpen.remove(event.getPlayer().getUniqueId());
    }
}
