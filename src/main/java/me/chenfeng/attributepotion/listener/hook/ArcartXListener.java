package me.chenfeng.attributepotion.listener.hook;

import me.chenfeng.attributepotion.manager.ConfigManager;
import me.chenfeng.attributepotion.utils.LoggerUtil;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.Locale;
import java.util.Set;

public class ArcartXListener {
    private static final String CATEGORY = "AttributePotion";
    private static final String KEY_PREFIX = "attribute_potion_";

    private Object keyBindRegistry;
    private Class<?> keyCallBackClass;
    private Method registerClientKeyBindMethod;
    private Method getArcartXHandlerMethod;
    private Method getSlotItemStackMethod;
    private Method setSlotItemStackMethod;

    public ArcartXListener() {
        try {
            loadApi();
            registerKeys();
        } catch (ReflectiveOperationException e) {
            LoggerUtil.warning("[AttributePotion] ArcartX hook disabled: " + e.getMessage());
        }
    }

    private void loadApi() throws ReflectiveOperationException {
        Class<?> apiClass = Class.forName("priv.seventeen.artist.arcartx.api.ArcartXAPI");
        Class<?> playerUtilsClass = Class.forName("priv.seventeen.artist.arcartx.util.PlayerUtils");
        Class<?> arcartXPlayerClass = Class.forName("priv.seventeen.artist.arcartx.core.entity.data.ArcartXPlayer");

        keyCallBackClass = Class.forName("priv.seventeen.artist.arcartx.util.collections.KeyCallBack");
        keyBindRegistry = apiClass.getMethod("getKeyBindRegistry").invoke(null);
        registerClientKeyBindMethod = findRegisterClientKeyBindMethod(keyBindRegistry.getClass());
        getArcartXHandlerMethod = playerUtilsClass.getMethod("getArcartXHandler", Player.class);
        getSlotItemStackMethod = arcartXPlayerClass.getMethod("getSlotItemStack", String.class);
        setSlotItemStackMethod = arcartXPlayerClass.getMethod("setSlotItemStack", String.class, ItemStack.class);
    }

    private Method findRegisterClientKeyBindMethod(Class<?> registryClass) throws NoSuchMethodException {
        for (Method method : registryClass.getMethods()) {
            Class<?>[] parameterTypes = method.getParameterTypes();
            if (method.getName().equals("registerClientKeyBind")
                    && parameterTypes.length == 4
                    && parameterTypes[0] == String.class
                    && parameterTypes[1] == String.class
                    && parameterTypes[2] == String.class
                    && parameterTypes[3].isAssignableFrom(keyCallBackClass)) {
                return method;
            }
        }
        throw new NoSuchMethodException("registerClientKeyBind(String, String, String, KeyCallBack)");
    }

    private void registerKeys() throws ReflectiveOperationException {
        Set<String> keys = ConfigManager.getRegisterKeys();
        if (keys == null || keys.isEmpty()) {
            return;
        }

        for (String key : keys) {
            registerKey(key);
        }
    }

    private void registerKey(String rawKey) throws ReflectiveOperationException {
        String normalized = KeySlotPotionHandler.normalizeKey(rawKey);
        Object callback = Proxy.newProxyInstance(
                keyCallBackClass.getClassLoader(),
                new Class<?>[]{keyCallBackClass},
                (proxy, method, args) -> {
                    if (args == null || args.length == 0 || !(args[0] instanceof Player)) {
                        return null;
                    }

                    Player player = (Player) args[0];
                    if (method.getName().equals("onPress")) {
                        handleKeyPress(player, normalized);
                    } else if (method.getName().equals("onRelease")) {
                        handleKeyRelease(player, normalized);
                    }
                    return null;
                }
        );

        registerClientKeyBindMethod.invoke(
                keyBindRegistry,
                KEY_PREFIX + normalized.toLowerCase(Locale.ROOT),
                CATEGORY,
                normalized,
                callback
        );
    }

    private void handleKeyPress(Player player, String key) {
        String slot = KeySlotPotionHandler.resolveSlot(key);
        Object arcartXPlayer = getArcartXPlayer(player);
        if (slot == null || arcartXPlayer == null) {
            KeySlotPotionHandler.interruptActive(player, null);
            return;
        }

        ItemStack itemStack = getSlotItemStack(arcartXPlayer, slot);
        KeySlotPotionHandler.handleKeyPress(
                player,
                key,
                itemStack,
                updatedItem -> setSlotItemStack(arcartXPlayer, slot, updatedItem),
                null
        );
    }

    private void handleKeyRelease(Player player, String key) {
        String slot = KeySlotPotionHandler.resolveSlot(key);
        Object arcartXPlayer = getArcartXPlayer(player);
        if (slot == null || arcartXPlayer == null) {
            KeySlotPotionHandler.interruptActive(player, null);
            return;
        }

        ItemStack itemStack = getSlotItemStack(arcartXPlayer, slot);
        KeySlotPotionHandler.useSlotPotionOnRelease(
                player,
                key,
                itemStack,
                updatedItem -> setSlotItemStack(arcartXPlayer, slot, updatedItem),
                null
        );
    }

    private Object getArcartXPlayer(Player player) {
        try {
            return getArcartXHandlerMethod.invoke(null, player);
        } catch (ReflectiveOperationException e) {
            LoggerUtil.warning("[AttributePotion] Failed to get ArcartX player handler: " + e.getMessage());
            return null;
        }
    }

    private ItemStack getSlotItemStack(Object arcartXPlayer, String slot) {
        try {
            return (ItemStack) getSlotItemStackMethod.invoke(arcartXPlayer, slot);
        } catch (ReflectiveOperationException | ClassCastException e) {
            LoggerUtil.warning("[AttributePotion] Failed to get ArcartX slot item: " + e.getMessage());
            return null;
        }
    }

    private void setSlotItemStack(Object arcartXPlayer, String slot, ItemStack itemStack) {
        try {
            setSlotItemStackMethod.invoke(arcartXPlayer, slot, itemStack);
        } catch (ReflectiveOperationException e) {
            LoggerUtil.warning("[AttributePotion] Failed to update ArcartX slot item: " + e.getMessage());
        }
    }
}
