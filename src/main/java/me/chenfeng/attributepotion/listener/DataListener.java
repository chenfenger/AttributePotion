package me.chenfeng.attributepotion.listener;

import me.chenfeng.attributepotion.AttributePotion;
import me.chenfeng.attributepotion.data.PlayerProfile;
import me.chenfeng.attributepotion.data.PotionConfig;
import me.chenfeng.attributepotion.manager.ConfigManager;
import me.chenfeng.attributepotion.manager.PlayerManager;
import me.chenfeng.attributepotion.manager.constructor.OptionKey;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;

import java.util.ArrayList;

public class DataListener implements Listener {

    @EventHandler
    public void onPlayerJoin(PlayerJoinEvent event) {
        PlayerManager.getOrCreateProfile(event.getPlayer());
        AttributePotion.getInstance().getSqlManager().loadProfileAsync(event.getPlayer());
    }

    @EventHandler
    public void onPlayerQuit(PlayerQuitEvent event) {
        removeConfiguredPotions(event.getPlayer(), OptionKey.QUIT);
        PlayerProfile profile = PlayerManager.getProfile(event.getPlayer());
        if (profile != null) {
            AttributePotion.getInstance().getSqlManager().saveProfileAsync(event.getPlayer().getUniqueId(), profile);
            PlayerManager.unloadProfile(event.getPlayer().getUniqueId());
        }
    }

    @EventHandler
    public void onPlayerDeath(PlayerDeathEvent event) {
        removeConfiguredPotions(event.getEntity(), OptionKey.DEATH);
    }

    private void removeConfiguredPotions(Player player, OptionKey key) {
        PlayerProfile profile = PlayerManager.getProfile(player);
        if (profile == null) {
            return;
        }

        for (String potionKey : new ArrayList<>(profile.getActivePotions().keySet())) {
            PotionConfig config = ConfigManager.getPotionConfig(potionKey);
            if (config != null && config.getOptional().isEnabled(key, false)) {
                PlayerManager.removeActivePotion(player, profile, potionKey);
            }
        }
    }

}
