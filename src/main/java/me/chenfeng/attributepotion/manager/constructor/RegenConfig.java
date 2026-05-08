package me.chenfeng.attributepotion.manager.constructor;

import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.ToString;
import me.chenfeng.attributepotion.manager.ConfigManager;

import java.util.Collections;
import java.util.EnumMap;
import java.util.Map;

/**
 * 再生配置类,用于存储药水效果期间的再生参数。
 * <p>
 * 原始字符串格式如 "10<->5<->2"，解析为：单次恢复数值、恢复时间、恢复模式。
 */
@Getter
@ToString
@EqualsAndHashCode
public class RegenConfig {
    /**
     * 再生数据内部类，存储单个再生类型的完整配置
     */
    @Getter
    @ToString
    @EqualsAndHashCode
    public static class RegenData {
        private final double amount;
        private final double duration;
        private final int mode;

        public RegenData(double amount, double duration, int mode) {
            this.amount = amount;
            this.duration = duration;
            this.mode = mode;
        }
    }

    /**
     * 原始配置字符串映射，键为再生类型，值为未解析的配置字符串。
     * <p>
     * 例如："10<->5<->2" 表示单次恢复数值、恢复时间、恢复模式。
     */
    private final EnumMap<RegenType, String> raw;

    /**
     * 解析后的再生数据映射。
     * <p>
     * 键为再生类型，值为解析后的再生数据（包含恢复数值、持续时间、恢复模式）。
     */
    private final EnumMap<RegenType, RegenData> parsed;

    /**
     * 构造再生配置对象，并自动解析所有原始字符串。
     * 
     * @param raw 再生类型到原始配置字符串的映射
     */
    public RegenConfig(Map<RegenType, String> raw) {
        EnumMap<RegenType, String> rawMap = new EnumMap<>(RegenType.class);
        EnumMap<RegenType, RegenData> parsedMap = new EnumMap<>(RegenType.class);
        
        if (raw != null) {
            for (Map.Entry<RegenType, String> e : raw.entrySet()) {
                if (e.getKey() != null && e.getValue() != null) {
                    String rawValue = e.getValue();
                    rawMap.put(e.getKey(), rawValue);
                    
                    RegenData data = parseRegenString(rawValue);
                    if (data != null) {
                        parsedMap.put(e.getKey(), data);
                    }
                }
            }
        }
        this.raw = rawMap;
        this.parsed = parsedMap;
    }

    /**
     * 解析再生配置字符串。
     * <p>
     * 格式: "单次恢复数值<->恢复时间<->恢复模式"
     * 例如: "10<->5<->2" 表示每次恢复10点，持续5秒，恢复模式为2
     * 
     * @param raw 原始配置字符串
     * @return 解析后的再生数据，如果解析失败则返回null
     */
    private RegenData parseRegenString(String raw) {
        if (raw == null || raw.isEmpty()) {
            return null;
        }
        
        try {
            String split = ConfigManager.getSplit();
            String[] parts = raw.split(java.util.regex.Pattern.quote(split), 3);
            
            if (parts.length != 3) {
                return null;
            }
            
            double amount = Double.parseDouble(parts[0].trim());
            double duration = Double.parseDouble(parts[1].trim());
            int mode = Integer.parseInt(parts[2].trim());
            
            return new RegenData(amount, duration, mode);
        } catch (NumberFormatException e) {
            return null;
        }
    }

    /**
     * 获取指定再生类型的原始配置字符串。
     * 
     * @param type 再生类型枚举
     * @return 对应的原始配置字符串，如果不存在则返回null
     */
    public String getRaw(RegenType type) {
        return raw.get(type);
    }

    /**
     * 获取指定再生类型的解析后数据。
     * 
     * @param type 再生类型枚举
     * @return 对应的再生数据，如果不存在或未解析成功则返回null
     */
    public RegenData getData(RegenType type) {
        return parsed.get(type);
    }

    /**
     * 获取所有再生配置的不可变映射视图（原始字符串）。
     * 
     * @return 不可变的再生类型到配置字符串的映射
     */
    public Map<RegenType, String> asMap() {
        return Collections.unmodifiableMap(raw);
    }

    /**
     * 获取所有解析后的再生数据的不可变映射视图。
     * 
     * @return 不可变的再生类型到再生数据的映射
     */
    public Map<RegenType, RegenData> asParsedMap() {
        return Collections.unmodifiableMap(parsed);
    }
}
