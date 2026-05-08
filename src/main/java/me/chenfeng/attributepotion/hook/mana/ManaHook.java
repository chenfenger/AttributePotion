package me.chenfeng.attributepotion.hook.mana;

import org.bukkit.entity.Player;

/**
 * 魔力系统适配接口。
 * <p>
 * 药水恢复逻辑只依赖这个接口，不直接依赖 SkillAPI、Yetzirah 等外部插件类，
 * 这样缺少对应插件时不会触发 NoClassDefFoundError。
 */
public interface ManaHook {

    /**
     * 获取 Hook 名称，用于日志输出。
     *
     * @return Hook 名称
     */
    String getName();

    /**
     * 判断当前 Hook 是否可用。
     *
     * @return 可用返回 true
     */
    boolean isAvailable();

    /**
     * 获取玩家当前魔力值。
     *
     * @param player 玩家
     * @return 当前魔力值，读取失败时返回 0
     */
    double getCurrentMana(Player player);

    /**
     * 获取玩家最大魔力值。
     *
     * @param player 玩家
     * @return 最大魔力值，读取失败时返回 0
     */
    double getMaxMana(Player player);

    /**
     * 设置玩家当前魔力值。
     *
     * @param player 玩家
     * @param value 新魔力值
     */
    void setMana(Player player, double value);

    /**
     * 按变化量调整玩家魔力，并限制在 0 到最大魔力之间。
     *
     * @param player 玩家
     * @param amount 魔力变化量
     */
    default void addMana(Player player, double amount) {
        double maxMana = getMaxMana(player);
        if (maxMana <= 0) {
            return;
        }

        double currentMana = getCurrentMana(player);
        setMana(player, Math.max(0, Math.min(maxMana, currentMana + amount)));
    }
}
