package me.chenfeng.attributepotion.listener.hook;

import me.chenfeng.attributepotion.AttributePotion;
import me.chenfeng.attributepotion.manager.ConfigManager;
import me.chenfeng.attributepotion.utils.LoggerUtil;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.Event;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.inventory.ItemStack;

import java.lang.reflect.Method;
import java.util.concurrent.CompletionStage;
import java.util.Set;

public class CloudPickListener implements Listener {
    private final AttributePotion plugin;
    private Method getKeyMethod;
    private Method getPlayerMethod;
    private Method getSlotItemMethod;
    private Method setSlotItemMethod;

    public CloudPickListener(AttributePotion plugin) {
        this.plugin = plugin;
        try {
            loadCloudPickApi();
            registerKeys();
            registerKeyEvents();
        } catch (ReflectiveOperationException e) {
            LoggerUtil.warning("[AttributePotion] CloudPick hook disabled: " + e.getMessage());
        }
    }

    private void loadCloudPickApi() throws ReflectiveOperationException {
        Class<?> futureSlotApiClass = Class.forName("yslelf.cloudpick.bukkit.api.FutureSlotAPI");
        Class<?> keyPressEventClass = Class.forName("yslelf.cloudpick.bukkit.api.event.KeyPressEvent");

        getKeyMethod = keyPressEventClass.getMethod("getKey");
        getPlayerMethod = keyPressEventClass.getMethod("getPlayer");
        getSlotItemMethod = futureSlotApiClass.getMethod("getSlotItem", Player.class, String.class);
        setSlotItemMethod = futureSlotApiClass.getMethod("setSlotItem", Player.class, String.class, ItemStack.class, boolean.class);
    }

    private void registerKeys() throws ReflectiveOperationException {
        Set<String> keys = ConfigManager.getRegisterKeys();
        if (keys == null || keys.isEmpty()) {
            return;
        }

        Class<?> keyApiClass = Class.forName("yslelf.cloudpick.bukkit.api.KeyAPI");
        Method registerKeyMethod = keyApiClass.getMethod("registerKey", String.class);
        for (String key : keys) {
            registerKeyMethod.invoke(null, key);
        }
    }

    @SuppressWarnings("unchecked")
    private void registerKeyEvents() throws ReflectiveOperationException {
        registerKeyEvent("yslelf.cloudpick.bukkit.api.event.KeyPressEvent", false);
        registerKeyEvent("yslelf.cloudpick.bukkit.api.event.KeyReleaseEvent", true);
    }

    @SuppressWarnings("unchecked")
    private void registerKeyEvent(String className, boolean release) throws ReflectiveOperationException {
        Class<? extends Event> eventClass = (Class<? extends Event>) Class
                .forName(className)
                .asSubclass(Event.class);

        Bukkit.getPluginManager().registerEvent(
                eventClass,
                this,
                EventPriority.NORMAL,
                (listener, event) -> {
                    if (release) {
                        handleKeyRelease(event);
                    } else {
                        handleKeyPress(event);
                    }
                },
                plugin
        );
    }

    private void handleKeyPress(Event event) {
        try {
            String key = (String) getKeyMethod.invoke(event);
            Player player = (Player) getPlayerMethod.invoke(event);
            String slot = KeySlotPotionHandler.resolveSlot(key);
            if (slot == null) {
                KeySlotPotionHandler.interruptActive(player, null);
                return;
            }

            Object future = getSlotItemMethod.invoke(null, player, slot);
            if (!(future instanceof CompletionStage)) {
                return;
            }

            ((CompletionStage<?>) future).thenAccept(itemStack ->
                    Bukkit.getScheduler().runTask(plugin, () -> KeySlotPotionHandler.handleKeyPress(
                            player,
                            key,
                            (ItemStack) itemStack,
                            updatedItem -> setSlotItem(player, slot, updatedItem),
                            null
                    )));
        } catch (ReflectiveOperationException | ClassCastException e) {
            LoggerUtil.warning("[AttributePotion] Failed to handle CloudPick key: " + e.getMessage());
        }
    }

    private void handleKeyRelease(Event event) {
        try {
            String key = (String) getKeyMethod.invoke(event);
            Player player = (Player) getPlayerMethod.invoke(event);
            String slot = KeySlotPotionHandler.resolveSlot(key);
            if (slot == null) {
                KeySlotPotionHandler.interruptActive(player, null);
                return;
            }

            Object future = getSlotItemMethod.invoke(null, player, slot);
            if (!(future instanceof CompletionStage)) {
                return;
            }

            ((CompletionStage<?>) future).thenAccept(itemStack ->
                    Bukkit.getScheduler().runTask(plugin, () -> KeySlotPotionHandler.useSlotPotionOnRelease(
                            player,
                            key,
                            (ItemStack) itemStack,
                            updatedItem -> setSlotItem(player, slot, updatedItem)
                    )));
        } catch (ReflectiveOperationException | ClassCastException e) {
            LoggerUtil.warning("[AttributePotion] Failed to handle CloudPick key release: " + e.getMessage());
        }
    }

    private void setSlotItem(Player player, String slot, ItemStack itemStack) {
        try {
            setSlotItemMethod.invoke(null, player, slot, itemStack, true);
        } catch (ReflectiveOperationException e) {
            LoggerUtil.warning("[AttributePotion] Failed to update CloudPick slot: " + e.getMessage());
        }
    }
}
