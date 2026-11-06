package me.chenfeng.attributepotion.listener;

import me.chenfeng.attributepotion.AttributePotion;
import me.chenfeng.attributepotion.data.ActivePotion;
import me.chenfeng.attributepotion.data.PlayerProfile;
import me.chenfeng.attributepotion.data.PotionConfig;
import me.chenfeng.attributepotion.handler.CommandHandler;
import me.chenfeng.attributepotion.handler.PotionHandler;
import me.chenfeng.attributepotion.listener.hook.KeySlotPotionHandler;
import me.chenfeng.attributepotion.manager.ConfigManager;
import me.chenfeng.attributepotion.manager.PlayerManager;
import me.chenfeng.attributepotion.manager.constructor.OptionKey;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerRespawnEvent;

import java.util.ArrayList;
import java.util.Map;
import java.util.UUID;

public class DataListener implements Listener {

    @EventHandler
    public void onPlayerJoin(PlayerJoinEvent event) {
        PlayerManager.getOrCreateProfile(event.getPlayer());
        AttributePotion.getInstance().getSqlManager().loadProfileAsync(event.getPlayer());
    }

    @EventHandler
    public void onPlayerQuit(PlayerQuitEvent event) {
        Player player = event.getPlayer();
        UUID uuid = player.getUniqueId();

        PlayerProfile profile = PlayerManager.getProfile(player);
        if (profile != null) {
            // 先结算已到期的药水，避免它们的 end 动作被存进数据库等到下次登录才执行
            PlayerManager.removeExpiredPotions(player, profile);
            removeConfiguredPotions(player, OptionKey.QUIT);
            AttributePotion.getInstance().getSqlManager().saveProfileAsync(uuid, profile);
            PlayerManager.unloadProfile(uuid);
        }

        KeySlotPotionHandler.clearPlayer(uuid);
        CommandHandler.clearActionBossBars(uuid);
        ConfigManager.clearMessageCooldown(uuid);
    }

    @EventHandler
    public void onPlayerDeath(PlayerDeathEvent event) {
        removeConfiguredPotions(event.getEntity(), OptionKey.DEATH);
    }

    /**
     * 原版在死亡时会清空药水效果，这里给保留下来的药水重新补上剩余时长。
     */
    @EventHandler
    public void onPlayerRespawn(PlayerRespawnEvent event) {
        Player player = event.getPlayer();
        Bukkit.getScheduler().runTaskLater(AttributePotion.getInstance(), () -> {
            PlayerProfile profile = PlayerManager.getProfile(player);
            if (profile == null || Bukkit.getPlayer(player.getUniqueId()) != player) {
                return;
            }
            for (Map.Entry<String, ActivePotion> entry : profile.getActivePotions().entrySet()) {
                PotionConfig config = ConfigManager.getPotionConfig(entry.getKey());
                if (config != null) {
                    PotionHandler.applyRemainingVanillaEffects(player, config, entry.getValue());
                }
            }
        }, 1L);
    }

    private void removeConfiguredPotions(Player player, OptionKey key) {
        PlayerProfile profile = PlayerManager.getProfile(player);
        if (profile == null) {
            return;
        }

        for (String potionKey : new ArrayList<>(profile.getActivePotions().keySet())) {
            PotionConfig config = ConfigManager.getPotionConfig(potionKey);
            if (config == null || !config.getOptional().isEnabled(key, false)) {
                continue;
            }
            // optional.death 对永久药水无效
            if (key == OptionKey.DEATH && config.getTime() < 0) {
                continue;
            }
            PlayerManager.removeActivePotion(player, profile, potionKey);
        }
    }
}
