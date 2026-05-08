package me.chenfeng.attributepotion.manager.constructor;

/**
 * 可选配置项枚举。
 * <p>
 * 定义了药水可以启用的各种可选行为和功能。
 */
public enum OptionKey {
    /**
     * 是否消耗物品
     */
    CONSUME,
    
    /**
     * 是否覆盖已有效果
     */
    COVER,
    
    /**
     * 是否需要潜行状态
     */
    SHIFT,
    
    /**
     * 死亡时是否移除效果
     */
    DEATH,
    
    /**
     * 退出游戏时是否移除效果
     */
    QUIT,
    
    /**
     * 是否启用冷却时间
     */
    COOL,
    
    /**
     * 是否启用范围检测
     */
    RANGE,

    /**
     * NBT 次数耗尽时是否移除物品。
     */
    BREAK,

    /**
     * 是否显示药水剩余时间 BossBar。
     */
    BOSSBAR;

    /**
     * 从配置字符串解析可选配置项枚举。
     * <p>
     * 支持大小写不敏感的匹配。
     * 
     * @param raw 原始配置字符串
     * @return 对应的枚举常量，如果解析失败则返回null
     */
    public static OptionKey fromConfig(String raw) {
        if (raw == null) return null;
        String s = raw.trim();
        if (s.isEmpty()) return null;
        try {
            return OptionKey.valueOf(s.toUpperCase());
        } catch (IllegalArgumentException ignored) {
            return null;
        }
    }
}
