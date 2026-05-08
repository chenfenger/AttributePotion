package me.chenfeng.attributepotion.manager.constructor;

/**
 * 药水触发器类型枚举。
 * <p>
 * 定义了药水可以被触发的各种方式和时机。
 * 使用枚举可以在加载时检测到拼写错误。
 */
public enum TriggerType {
    /**
     * 所有触发方式
     */
    ALL,
    
    /**
     * 左键点击空气
     */
    LEFT_CLICK_AIR,
    
    /**
     * 左键点击方块
     */
    LEFT_CLICK_BLOCK,
    
    /**
     * 右键点击空气
     */
    RIGHT_CLICK_AIR,
    
    /**
     * 右键点击方块
     */
    RIGHT_CLICK_BLOCK,
    
    /**
     * 按键触发
     */
    KEY;

    /**
     * 从配置字符串解析触发器类型枚举。
     * <p>
     * 支持大小写不敏感的匹配。
     * 
     * @param raw 原始配置字符串
     * @return 对应的枚举常量，如果解析失败则返回null
     */
    public static TriggerType fromConfig(String raw) {
        if (raw == null) return null;
        String s = raw.trim().toUpperCase();
        if (s.isEmpty()) return null;
        if (s.equals("DRAGON") || s.equals("DRAGONCORE")
                || s.equals("GERM") || s.equals("GERMPLUGIN")
                || s.equals("CLOUD") || s.equals("CLOUDPICK")) {
            return KEY;
        }
        try {
            return TriggerType.valueOf(s);
        } catch (IllegalArgumentException ignored) {
            return null;
        }
    }
}
