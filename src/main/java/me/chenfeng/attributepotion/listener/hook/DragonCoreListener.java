package me.chenfeng.attributepotion.listener.hook;

import com.google.common.collect.ImmutableMap;
import eos.moe.dragoncore.api.CoreAPI;
import eos.moe.dragoncore.api.FutureSlotAPI;
import eos.moe.dragoncore.api.event.KeyPressEvent;
import eos.moe.dragoncore.api.event.KeyReleaseEvent;
import eos.moe.dragoncore.network.PacketSender;
import me.chenfeng.attributepotion.AttributePotion;
import me.chenfeng.attributepotion.data.PotionConfig;
import me.chenfeng.attributepotion.manager.ConfigManager;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.inventory.ItemStack;

import java.util.Set;
import java.util.concurrent.CompletableFuture;

public class DragonCoreListener implements Listener {
    private static final String PRESS_HUD_YAML_PATH = "Gui/AttributePotion/press-hud.yml";
    private static final String PRESS_HUD_NAME = "AttributePotion/press-hud";
    private static boolean isDragonCoreLoaded = false;
    private static AttributePotion plugin;
    private static final KeySlotPotionHandler.PressDisplayReporter DISPLAY_REPORTER = new KeySlotPotionHandler.PressDisplayReporter() {
        @Override
        public void report(Player player, String key, PotionConfig config, double progress) {
            sendPressProgress(player, key, config, progress);
        }

        @Override
        public void stop(Player player) {
            if (Bukkit.getPlayer(player.getUniqueId()) == player) {
                sendPressStop(player);
            }
        }
    };

    public DragonCoreListener(AttributePotion plugin) {
        DragonCoreListener.plugin = plugin;
        Set<String> keys = ConfigManager.getRegisterKeys();
        if(keys != null && !keys.isEmpty()) {
            for (String key : keys) {
                // 注册按键监听
                CoreAPI.registerKey(key);
            }
        }
        isDragonCoreLoaded = true;
    }

    @EventHandler
    public void onPlayerJoin(PlayerJoinEvent event) {
        if(isDragonCoreLoaded) {
            Player player = event.getPlayer();
            Bukkit.getScheduler().runTaskLater(plugin, () -> {
                if (Bukkit.getPlayer(player.getUniqueId()) == player) {
                    sendPressHud(player);
                }
            }, 20L);
        }
    }

    public static void sendYaml() {
        if(!isDragonCoreLoaded) {
            return;
        }
        Bukkit.getOnlinePlayers().forEach(DragonCoreListener::sendPressHud);
    }

    private static void sendPressHud(Player player) {
        PacketSender.sendYaml(player, PRESS_HUD_YAML_PATH, ConfigManager.getHudYaml());
        PacketSender.sendOpenHud(player, PRESS_HUD_NAME);
    }

    @EventHandler
    public void onKeyPress(KeyPressEvent event) {
        String key = event.getKey();
        Player player = event.getPlayer();
        String slot = KeySlotPotionHandler.resolveSlot(key);
        if(slot == null) {
            KeySlotPotionHandler.interruptActive(player, DISPLAY_REPORTER);
            return;
        }

        KeySlotPotionHandler.onKeyPress(player, key, slotAccess(player, slot), DISPLAY_REPORTER);
    }

    private static KeySlotPotionHandler.SlotAccess slotAccess(Player player, String slot) {
        return new KeySlotPotionHandler.SlotAccess() {
            @Override
            public CompletableFuture<ItemStack> read() {
                return FutureSlotAPI.getSlotItem(player, slot);
            }

            @Override
            public CompletableFuture<?> write(ItemStack itemStack) {
                return FutureSlotAPI.setSlotItem(player, slot, itemStack, true);
            }
        };
    }

    private static void sendPressProgress(Player player, String key, PotionConfig config, double progress) {
        String display = config.getMatch();
        if (display == null || display.isEmpty()) {
            display = config.getKey();
        }
        PacketSender.sendSyncPlaceholder(player, ImmutableMap.of(
                "apn_display", display,
                "apn_progress", String.valueOf(progress),
                "apn_stop", "false"
        ));
    }

    private static void sendPressStop(Player player) {
        PacketSender.sendSyncPlaceholder(player, ImmutableMap.of(
                "apn_display", "",
                "apn_start_time", "0",
                "apn_press_time", "0",
                "apn_progress", "0",
                "apn_stop", "true"
        ));
    }

    @EventHandler
    public void onKeyRelease(KeyReleaseEvent event) {
        String key = event.getKey();
        Player player = event.getPlayer();
        String slot = KeySlotPotionHandler.resolveSlot(key);
        if(slot == null) {
            KeySlotPotionHandler.interruptActive(player, DISPLAY_REPORTER);
            return;
        }

        KeySlotPotionHandler.onKeyRelease(player, key, slotAccess(player, slot), DISPLAY_REPORTER);
    }
}
