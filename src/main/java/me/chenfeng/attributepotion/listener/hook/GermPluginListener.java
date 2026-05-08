package me.chenfeng.attributepotion.listener.hook;

import com.germ.germplugin.api.GermKeyAPI;
import com.germ.germplugin.api.GermSlotAPI;
import com.germ.germplugin.api.KeyType;
import com.germ.germplugin.api.event.GermKeyDownEvent;
import com.germ.germplugin.api.event.GermKeyUpEvent;
import me.chenfeng.attributepotion.manager.ConfigManager;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.inventory.ItemStack;

import java.util.Set;

public class GermPluginListener implements Listener {
    public GermPluginListener() {
        Set<String> keys = ConfigManager.getRegisterKeys();
        if (keys != null && !keys.isEmpty()) {
            for (String key : keys) {
                KeyType keyType = toKeyType(key);
                if (keyType != null) {
                    GermKeyAPI.registerKey(keyType);
                }
            }
        }
    }

    @EventHandler
    public void onKeyPress(GermKeyDownEvent event) {
        String key = toConfigKey(event);
        String slot = KeySlotPotionHandler.resolveSlot(key);
        if (slot == null) {
            KeySlotPotionHandler.interruptActive(event.getPlayer(), null);
            return;
        }

        ItemStack itemStack = GermSlotAPI.getItemStackFromIdentity(event.getPlayer(), slot);
        KeySlotPotionHandler.handleKeyPress(
                event.getPlayer(),
                key,
                itemStack,
                updatedItem -> GermSlotAPI.saveItemStackToIdentity(event.getPlayer(), slot, updatedItem),
                null
        );
    }

    @EventHandler
    public void onKeyRelease(GermKeyUpEvent event) {
        String key = toConfigKey(event);
        String slot = KeySlotPotionHandler.resolveSlot(key);
        if (slot == null) {
            KeySlotPotionHandler.interruptActive(event.getPlayer(), null);
            return;
        }

        ItemStack itemStack = GermSlotAPI.getItemStackFromIdentity(event.getPlayer(), slot);
        KeySlotPotionHandler.useSlotPotionOnRelease(
                event.getPlayer(),
                key,
                itemStack,
                updatedItem -> GermSlotAPI.saveItemStackToIdentity(event.getPlayer(), slot, updatedItem)
        );
    }

    private String toConfigKey(GermKeyDownEvent event) {
        if (event.getKeyType() != null) {
            return event.getKeyType().getSimpleKey();
        }

        KeyType keyType = KeyType.getKeyTypeFromKeyId(event.getKeyID());
        return keyType == null ? null : keyType.getSimpleKey();
    }

    private String toConfigKey(GermKeyUpEvent event) {
        if (event.getKeyType() != null) {
            return event.getKeyType().getSimpleKey();
        }

        KeyType keyType = KeyType.getKeyTypeFromKeyId(event.getKeyID());
        return keyType == null ? null : keyType.getSimpleKey();
    }

    private KeyType toKeyType(String key) {
        String normalized = KeySlotPotionHandler.normalizeKey(key);
        try {
            return KeyType.valueOf("KEY_" + normalized);
        } catch (IllegalArgumentException ignored) {
            try {
                return KeyType.valueOf(normalized);
            } catch (IllegalArgumentException ignoredAgain) {
                return null;
            }
        }
    }
}
