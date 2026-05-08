package me.chenfeng.attributepotion.hook.mana;

import me.chenfeng.attributepotion.utils.LoggerUtil;
import org.bukkit.entity.Player;

import java.lang.reflect.Method;

/**
 * Yetzirah 魔力 Hook。
 */
public class YetzirahManaHook extends AbstractReflectionManaHook {

    private Method hasProfileMethod;
    private Method getProfileMethod;
    private Method getMainClassMethod;
    private Method getManaMethod;
    private Method getMaxManaMethod;
    private Method setManaMethod;
    private Method forceSetManaMethod;

    /**
     * 创建 Yetzirah Hook，并在构造时检查所需方法是否存在。
     */
    public YetzirahManaHook() {
        super("Yetzirah");
        try {
            Class<?> apiClass = Class.forName("me.mkbaka.yetzirah.api.YetzirahAPI");
            Class<?> profileClass = Class.forName("me.mkbaka.yetzirah.api.data.PlayerProfile");
            Class<?> playerClass = Class.forName("me.mkbaka.yetzirah.api.data.pclass.PlayerClass");

            hasProfileMethod = apiClass.getMethod("hasProfile", Player.class);
            getProfileMethod = apiClass.getMethod("getProfile", Player.class);
            getMainClassMethod = profileClass.getMethod("getMainClass");
            getManaMethod = playerClass.getMethod("getMana");
            getMaxManaMethod = playerClass.getMethod("getMaxMana");
            forceSetManaMethod = findMethod(playerClass, "forceSetMana", double.class);
            setManaMethod = findMethod(playerClass, "setMana", double.class);

            available = forceSetManaMethod != null || setManaMethod != null;
        } catch (Throwable throwable) {
            LoggerUtil.debug("[AttributePotion] Yetzirah 魔力 Hook 不可用: " + throwable.getMessage());
        }
    }

    @Override
    public double getCurrentMana(Player player) {
        Object playerClass = getMainClass(player);
        return playerClass == null ? 0 : asDouble(invoke(playerClass, getManaMethod));
    }

    @Override
    public double getMaxMana(Player player) {
        Object playerClass = getMainClass(player);
        return playerClass == null ? 0 : asDouble(invoke(playerClass, getMaxManaMethod));
    }

    @Override
    public void setMana(Player player, double value) {
        Object playerClass = getMainClass(player);
        if (playerClass == null) {
            return;
        }

        if (forceSetManaMethod != null) {
            invoke(playerClass, forceSetManaMethod, value);
            return;
        }
        if (setManaMethod != null) {
            invoke(playerClass, setManaMethod, value);
        }
    }

    /**
     * 获取玩家主职业对象。
     *
     * @param player 玩家
     * @return 主职业对象，不存在时返回 null
     */
    private Object getMainClass(Player player) {
        if (!available || player == null || !player.isOnline()) {
            return null;
        }

        Object hasProfile = invoke(null, hasProfileMethod, player);
        if (hasProfile instanceof Boolean && !((Boolean) hasProfile)) {
            return null;
        }

        Object profile = invoke(null, getProfileMethod, player);
        if (profile == null) {
            return null;
        }

        return invoke(profile, getMainClassMethod);
    }

    /**
     * 查找可选方法。
     *
     * @param type 类
     * @param name 方法名
     * @param parameterTypes 参数类型
     * @return 方法，不存在时返回 null
     */
    private Method findMethod(Class<?> type, String name, Class<?>... parameterTypes) {
        try {
            return type.getMethod(name, parameterTypes);
        } catch (NoSuchMethodException ignored) {
            return null;
        }
    }
}
