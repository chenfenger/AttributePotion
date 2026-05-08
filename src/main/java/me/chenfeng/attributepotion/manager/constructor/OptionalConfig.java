package me.chenfeng.attributepotion.manager.constructor;

import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.ToString;

import java.util.Collections;
import java.util.EnumMap;
import java.util.Map;

/**
 * 可选配置类，用于存储药水的各种布尔型开关选项。
 * <p>
 * 包含消耗、覆盖、潜行、死亡处理等可选行为的配置。
 */
@Getter
@ToString
@EqualsAndHashCode
public class OptionalConfig {
    /**
     * 可选配置项到布尔值的映射。
     * <p>
     * 键为配置项类型，值为是否启用该功能。
     */
    private final EnumMap<OptionKey, Boolean> values;

    /**
     * 构造可选配置对象。
     * 
     * @param values 可选配置项到布尔值的映射
     */
    public OptionalConfig(Map<OptionKey, Boolean> values) {
        EnumMap<OptionKey, Boolean> map = new EnumMap<>(OptionKey.class);
        if (values != null) {
            for (Map.Entry<OptionKey, Boolean> e : values.entrySet()) {
                if (e.getKey() != null && e.getValue() != null) {
                    map.put(e.getKey(), e.getValue());
                }
            }
        }
        this.values = map;
    }

    /**
     * 检查指定的可选配置项是否启用。
     * 
     * @param key 配置项枚举
     * @param def 默认值，当配置项不存在时使用
     * @return 配置项的值，如果不存在则返回默认值
     */
    public boolean isEnabled(OptionKey key, boolean def) {
        Boolean v = values.get(key);
        return v != null ? v : def;
    }

    /**
     * 获取所有可选配置的不可变映射视图。
     * 
     * @return 不可变的配置项到布尔值的映射
     */
    public Map<OptionKey, Boolean> asMap() {
        return Collections.unmodifiableMap(values);
    }
}

