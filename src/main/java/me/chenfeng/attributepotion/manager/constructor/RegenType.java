package me.chenfeng.attributepotion.manager.constructor;

/**
 * 再生类型枚举。
 * <p>
 * 定义了药水可以恢复的各种属性类型。
 */
public enum RegenType {
    /**
     * 生命值恢复
     */
    HEALTH,
    
    /**
     * 法力值恢复
     */
    MANA,
    
    /**
     * 饥饿值恢复
     */
    HUNGER;

    /**
     * 从配置字符串解析再生类型枚举。
     * <p>
     * 支持大小写不敏感的匹配。
     * 
     * @param raw 原始配置字符串
     * @return 对应的枚举常量，如果解析失败则返回null
     */
    public static RegenType fromConfig(String raw) {
        if (raw == null) return null;
        String s = raw.trim();
        if (s.isEmpty()) return null;
        try {
            return RegenType.valueOf(s.toUpperCase());
        } catch (IllegalArgumentException ignored) {
            return null;
        }
    }
}

