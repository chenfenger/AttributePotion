package me.chenfeng.attributepotion.manager.constructor;

/**
 * 命令执行阶段枚举。
 * <p>
 * 定义了在药水生命周期的哪个阶段执行命令。
 */
public enum CommandPhase {
    /**
     * 药水效果成功应用时
     */
    SUCCESS,
    
    /**
     * 药水效果结束时
     */
    END,
    
    /**
     * 周期性执行（tick）
     */
    TICK;

    /**
     * 从配置字符串解析命令阶段枚举。
     * <p>
     * 支持大小写不敏感的匹配。
     * 
     * @param raw 原始配置字符串
     * @return 对应的枚举常量，如果解析失败则返回null
     */
    public static CommandPhase fromConfig(String raw) {
        if (raw == null) return null;
        String s = raw.trim();
        if (s.isEmpty()) return null;
        try {
            return CommandPhase.valueOf(s.toUpperCase());
        } catch (IllegalArgumentException ignored) {
            return null;
        }
    }
}

