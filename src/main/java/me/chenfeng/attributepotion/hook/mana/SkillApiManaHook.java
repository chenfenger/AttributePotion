package me.chenfeng.attributepotion.hook.mana;

import me.chenfeng.attributepotion.utils.LoggerUtil;
import org.bukkit.entity.Player;

import java.lang.reflect.Method;

/**
 * SkillAPI 魔力 Hook。
 */
public class SkillApiManaHook extends AbstractReflectionManaHook {

    private Method hasPlayerDataMethod;
    private Method getPlayerDataMethod;
    private Method getManaMethod;
    private Method getMaxManaMethod;
    private Method setManaMethod;

    /**
     * 创建 SkillAPI Hook，并在构造时检查所需方法是否存在。
     */
    public SkillApiManaHook() {
        super("SkillAPI");
        try {
            Class<?> skillApiClass = Class.forName("com.sucy.skill.SkillAPI");
            Class<?> playerDataClass = Class.forName("com.sucy.skill.api.player.PlayerData");

            hasPlayerDataMethod = skillApiClass.getMethod("hasPlayerData", Player.class);
            getPlayerDataMethod = skillApiClass.getMethod("getPlayerData", Player.class);
            getManaMethod = playerDataClass.getMethod("getMana");
            getMaxManaMethod = playerDataClass.getMethod("getMaxMana");
            setManaMethod = playerDataClass.getMethod("setMana", double.class);

            available = true;
        } catch (Throwable throwable) {
            LoggerUtil.debug("[AttributePotion] SkillAPI 魔力 Hook 不可用: " + throwable.getMessage());
        }
    }

    @Override
    public double getCurrentMana(Player player) {
        Object data = getPlayerData(player);
        return data == null ? 0 : asDouble(invoke(data, getManaMethod));
    }

    @Override
    public double getMaxMana(Player player) {
        Object data = getPlayerData(player);
        return data == null ? 0 : asDouble(invoke(data, getMaxManaMethod));
    }

    @Override
    public void setMana(Player player, double value) {
        Object data = getPlayerData(player);
        if (data != null) {
            invoke(data, setManaMethod, value);
        }
    }

    /**
     * 获取 SkillAPI 玩家数据。
     *
     * @param player 玩家
     * @return 玩家数据，不存在时返回 null
     */
    private Object getPlayerData(Player player) {
        if (!available || player == null || !player.isOnline()) {
            return null;
        }

        Object hasData = invoke(null, hasPlayerDataMethod, player);
        if (!(hasData instanceof Boolean) || !((Boolean) hasData)) {
            return null;
        }

        return invoke(null, getPlayerDataMethod, player);
    }
}
