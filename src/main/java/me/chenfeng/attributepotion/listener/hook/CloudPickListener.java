package me.chenfeng.attributepotion.listener.hook;

import me.chenfeng.attributepotion.AttributePotion;
import me.chenfeng.attributepotion.manager.ConfigManager;
import me.chenfeng.attributepotion.utils.LoggerUtil;
import org.bukkit.Bukkit;
import org.bukkit.entity.AnimalTamer;
import org.bukkit.entity.Player;
import org.bukkit.event.Event;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerEvent;
import org.bukkit.inventory.ItemStack;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;

/**
 * 云拾按键接入。云拾可能是 Java 16 编译的，Java 8 下不能作为编译依赖，所以全部走反射。
 */
public class CloudPickListener implements Listener {
    private final AttributePotion plugin;
    private Method getSlotItemMethod;
    private Method setSlotItemMethod;

    public CloudPickListener(AttributePotion plugin) {
        this.plugin = plugin;
        try {
            loadCloudPickApi();
            registerKeys();
            registerKeyEvent("yslelf.cloudpick.bukkit.api.event.KeyPressEvent", false);
            registerKeyEvent("yslelf.cloudpick.bukkit.api.event.KeyReleaseEvent", true);
        } catch (ReflectiveOperationException e) {
            LoggerUtil.warning("[AttributePotion] CloudPick hook disabled: " + e);
        }
    }

    private void loadCloudPickApi() throws ReflectiveOperationException {
        Class<?> futureSlotApiClass = Class.forName("yslelf.cloudpick.bukkit.api.FutureSlotAPI");
        // 云拾的参数类型是 AnimalTamer，getMethod 不会按子类匹配，不能写 Player.class
        getSlotItemMethod = futureSlotApiClass.getMethod("getSlotItem", AnimalTamer.class, String.class);
        setSlotItemMethod = futureSlotApiClass.getMethod("setSlotItem", AnimalTamer.class, String.class, ItemStack.class, boolean.class);
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

    private void registerKeyEvent(String className, boolean release) throws ReflectiveOperationException {
        Class<? extends Event> eventClass = Class.forName(className).asSubclass(Event.class);
        // 按下和松开是两个没有继承关系的事件类，getKey 要各取各的
        Method getKeyMethod = eventClass.getMethod("getKey");

        Bukkit.getPluginManager().registerEvent(
                eventClass,
                this,
                EventPriority.NORMAL,
                (listener, event) -> {
                    if (eventClass.isInstance(event)) {
                        handleKeyEvent(event, getKeyMethod, release);
                    }
                },
                plugin
        );
    }

    private void handleKeyEvent(Event event, Method getKeyMethod, boolean release) {
        String key;
        try {
            key = (String) getKeyMethod.invoke(event);
        } catch (ReflectiveOperationException e) {
            LoggerUtil.warning("[AttributePotion] Failed to read CloudPick key: " + e);
            return;
        }

        Player player = ((PlayerEvent) event).getPlayer();
        String slot = KeySlotPotionHandler.resolveSlot(key);
        if (slot == null) {
            KeySlotPotionHandler.interruptActive(player, null);
        } else if (release) {
            KeySlotPotionHandler.onKeyRelease(player, key, slotAccess(player, slot), null);
        } else {
            KeySlotPotionHandler.onKeyPress(player, key, slotAccess(player, slot), null);
        }
    }

    private KeySlotPotionHandler.SlotAccess slotAccess(Player player, String slot) {
        return new KeySlotPotionHandler.SlotAccess() {
            @Override
            public CompletableFuture<ItemStack> read() {
                return invokeFuture(getSlotItemMethod, player, slot);
            }

            @Override
            public CompletableFuture<?> write(ItemStack itemStack) {
                return invokeFuture(setSlotItemMethod, player, slot, itemStack, true);
            }
        };
    }

    @SuppressWarnings("unchecked")
    private <T> CompletableFuture<T> invokeFuture(Method method, Object... args) {
        CompletableFuture<T> result = new CompletableFuture<>();
        try {
            Object future = method.invoke(null, args);
            if (future instanceof CompletionStage) {
                ((CompletionStage<T>) future).whenComplete((value, error) -> {
                    if (error != null) {
                        result.completeExceptionally(error);
                    } else {
                        result.complete(value);
                    }
                });
            } else {
                result.complete((T) future);
            }
        } catch (IllegalAccessException | InvocationTargetException | RuntimeException e) {
            result.completeExceptionally(e);
        }
        return result;
    }
}
