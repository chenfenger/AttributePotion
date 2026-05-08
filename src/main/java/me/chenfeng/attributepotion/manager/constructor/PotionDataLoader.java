package me.chenfeng.attributepotion.manager.constructor;

import me.chenfeng.attributepotion.data.PotionConfig;
import me.chenfeng.attributepotion.manager.ConfigManager;
import me.chenfeng.attributepotion.utils.LoggerUtil;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;

import java.io.File;
import java.util.*;

/**
 * 药水数据加载器，用于从YAML文件中加载药水定义。
 * <p>
 * 支持单个文件包含多个药水配置
 * 同时将键名映射为枚举类型，并在遇到未知键名时发出警告。
 */
public final class PotionDataLoader {
    private PotionDataLoader() {}

    /**
     * 从指定的YAML文件加载所有药水数据。
     * 
     * @param file 要加载的YAML文件
     */
    public static void load(File file) {
        YamlConfiguration yaml = YamlConfiguration.loadConfiguration(file);
        for (String potionKey : yaml.getKeys(false)) {
            ConfigurationSection sec = yaml.getConfigurationSection(potionKey);
            if (sec == null) continue;
            
            // 解析单个药水配置并存储到配置管理器
            PotionConfig potionConfig = parsePotion(potionKey, sec, file.getName());
            ConfigManager.putPotionConfig(potionKey, potionConfig);
        }
    }

    /**
     * 解析单个药水的配置数据。
     * 
     * @param key 药水的唯一标识键名
     * @param sec 药水配置的ConfigurationSection对象
     * @param sourceName 源文件名，用于错误日志
     * @return 解析完成的PotionData对象
     */
    private static PotionConfig parsePotion(String key, ConfigurationSection sec, String sourceName) {
        // 读取基础属性配置
        String match = (sec.getString("match") == null ? sec.getString("id", key) : sec.getString("match", key))
                .replace("&", "§");
        String group = sec.getString("group", "");
        double cooldown = sec.getDouble("cooldown", 0D);
        double press = sec.getDouble("press", 0D);
        double time = sec.getDouble("time", 0D);
        double distance = sec.getDouble("distance", 0D);

        // 解析触发器类型和条件列表
        Set<TriggerType> triggers = parseTriggers(sec.getStringList("triggers"), key, sourceName);
        List<String> conditions = new ArrayList<>();
        List<String> list = sec.getStringList("conditions");
        list.forEach(s -> conditions.add(s
                .replace("{split}", ConfigManager.getSplit())
                .replace("{id}", key)
                .replace("&", "§")
        ));

        // 读取属性和效果配置
        Map<String, String> attributes = readStringMap(sec.getConfigurationSection("attributes"));
        List<PotionEffect> effects = readPotionEffects(sec.getConfigurationSection("effects"), key, time, sourceName);

        // 解析再生、命令和可选配置
        RegenConfig regen = new RegenConfig(readEnumStringMap(sec.getConfigurationSection("regen"), RegenType.class, key, time, sourceName));
        ConfigurationSection section = sec.getConfigurationSection("actions") == null ? sec.getConfigurationSection("command") : sec.getConfigurationSection("actions");
        CommandConfig commands = parseCommandConfig(section, key, sourceName);
        OptionalConfig optional = new OptionalConfig(readEnumBooleanMap(sec.getConfigurationSection("optional"), OptionKey.class, key, sourceName));

        return new PotionConfig(
                key,
                match,
                group,
                cooldown,
                press,
                triggers,
                conditions,
                time,
                attributes,
                effects,
                regen,
                commands,
                distance,
                optional
        );
    }

    /**
     * 解析命令配置，包括普通阶段命令和 tick 周期命令。
     * 
     * @param sec 命令配置节对象
     * @param potionKey 药水键名，用于错误日志
     * @param sourceName 源文件名，用于错误日志
     * @return 解析完成的CommandConfig对象
     */
    private static CommandConfig parseCommandConfig(ConfigurationSection sec, String potionKey, String sourceName) {
        if (sec == null) {
            return new CommandConfig(Collections.emptyMap(), Collections.emptyMap());
        }
        
        // 解析普通阶段命令（START, FAILED, SUCCESS, END）
        Map<CommandPhase, List<String>> commands = new EnumMap<>(CommandPhase.class);
        for (CommandPhase phase : CommandPhase.values()) {
            if (phase == CommandPhase.TICK) continue;

            List<String> commandList = sec.getStringList(phase.name().toLowerCase());
            if (!commandList.isEmpty()) {
                List<String> list = new ArrayList<>();
                commandList.forEach(s -> list.add(s
                        .replace("{id}", potionKey)
                        .replace("&", "§")
                ));
                commands.put(phase, list);
            }
        }
        
        // 解析 tick 周期命令
        Map<Integer, List<String>> tickCommands = new TreeMap<>();
        ConfigurationSection tickSection = sec.getConfigurationSection("tick");
        if (tickSection != null) {
            for (String tickKey : tickSection.getKeys(false)) {
                try {
                    int tickInterval = Integer.parseInt(tickKey);
                    if (tickInterval <= 0) {
                        LoggerUtil.warning("Invalid tick interval '" + tickKey + "' in " + sourceName + " potion '" + potionKey + "', must be positive integer");
                        continue;
                    }
                    List<String> commandList = tickSection.getStringList(tickKey);
                    if (!commandList.isEmpty()) {
                        List<String> list = new ArrayList<>();
                        commandList.forEach(s -> list.add(s
                                .replace("{id}", potionKey)
                                .replace("&", "§")
                        ));
                        tickCommands.put(tickInterval, list);
                    }
                } catch (NumberFormatException e) {
                    LoggerUtil.warning("Invalid tick key '" + tickKey + "' in " + sourceName + " potion '" + potionKey + "', must be integer");
                }
            }
        }
        
        return new CommandConfig(commands, tickCommands);
    }

    /**
     * 解析触发器类型列表。
     * <p>
     * 支持特殊值"ALL"表示所有触发器类型。如果遇到未知的触发器类型，会记录警告并跳过。
     * 
     * @param raw 原始触发器字符串列表
     * @param potionKey 药水键名，用于错误日志
     * @param sourceName 源文件名，用于错误日志
     * @return 不可变的TriggerType集合
     */
    private static Set<TriggerType> parseTriggers(List<String> raw, String potionKey, String sourceName) {
        if (raw == null || raw.isEmpty()) return Collections.emptySet();

        EnumSet<TriggerType> set = EnumSet.noneOf(TriggerType.class);
        for (String s : raw) {
            TriggerType t = TriggerType.fromConfig(s);
            if (t == null) {
                LoggerUtil.warning("Unknown trigger '" + s + "' in " + sourceName + " potion '" + potionKey + "'");
                continue;
            }
            if (t == TriggerType.ALL) {
                return EnumSet.allOf(TriggerType.class);
            }
            set.add(t);
        }
        return Collections.unmodifiableSet(set);
    }

    /**
     * 读取字符串到字符串的映射配置。
     * 
     * @param sec 配置节对象
     * @return 不可变的字符串映射，如果配置节为空则返回空映射
     */
    private static Map<String, String> readStringMap(ConfigurationSection sec) {
        if (sec == null) return Collections.emptyMap();
        Map<String, String> map = new LinkedHashMap<>();
        for (String k : sec.getKeys(false)) {
            map.put(k, sec.getString(k, ""));
        }
        return Collections.unmodifiableMap(map);
    }

    /**
     * 读取药水效果配置，将字符串键名转换为PotionEffectType枚举并创建药水效果对象。
     * <p>
     * 该方法会遍历配置节中的所有键，将其作为药水效果类型名称进行解析，
     * 并根据配置值创建对应的PotionEffect对象。配置值格式为 "等级{split}持续时间"，
     * 其中 {time} 占位符会被替换为传入的时间参数。
     * </p>
     * <p>
     * 异常情况处理：
     * <ul>
     *   <li>未知的效果类型：记录警告并跳过该条目</li>
     *   <li>配置格式错误（分割后不足2部分）：记录警告并跳过</li>
     *   <li>数值解析失败：捕获NumberFormatException并记录警告</li>
     *   <li>负数值：等级或持续时间为负数时记录警告并跳过</li>
     *   <li>时间超限：效果持续时间超过药水总时间时自动截断并警告</li>
     * </ul>
     * 
     * @param sec 配置节对象，包含药水效果的配置数据。如果为null则返回空列表
     * @param potionKey 药水键名，用于错误日志中标识当前处理的药水
     * @param time 时间参数，用于替换配置值中的 {time} 占位符
     * @param sourceName 源文件名，用于错误日志中标识配置来源
     * @return 药水效果列表，所有成功解析的PotionEffect对象。如果配置为空或全部解析失败则返回空列表
     */
    private static List<PotionEffect> readPotionEffects(ConfigurationSection sec, String potionKey, double time, String sourceName) {
        if (sec == null) return Collections.emptyList();

        String split = ConfigManager.getSplit();
        List<PotionEffect> effects = new ArrayList<>();
        double maxSeconds = time > 0 ? time : Double.MAX_VALUE;
        
        for (String k : sec.getKeys(false)) {
            PotionEffectType type = PotionEffectType.getByName(k.toUpperCase(Locale.ROOT));
            if (type == null) {
                LoggerUtil.warning("Unknown effect '" + k + "' in " + sourceName + " potion '" + potionKey + "'");
                continue;
            }

            try {
                String replace = sec.getString(k, "")
                        .replace("{time}", String.valueOf(time))
                        .replace("{split}", split);
                
                String[] parts = replace.split(java.util.regex.Pattern.quote(split), 2);
                if (parts.length < 2) {
                    LoggerUtil.warning("Invalid format for effect '" + k + "' in " + sourceName + " potion '" + potionKey + "': expected format 'level" + split + "duration'");
                    continue;
                }
                
                int level = Integer.parseInt(parts[0].trim());
                double durationSeconds = Double.parseDouble(parts[1].trim());
                
                if (level <= 0 || durationSeconds < 0) {
                    LoggerUtil.warning("Invalid values for effect '" + k + "' in " + sourceName + " potion '" + potionKey + "': level=" + level + ", duration=" + durationSeconds);
                    continue;
                }
                
                if (durationSeconds > maxSeconds) {
                    LoggerUtil.warning("Effect duration exceeds potion time for '" + k + "' in " + sourceName + " potion '" + potionKey + "': " + durationSeconds + "s > " + maxSeconds + "s, clamped");
                    durationSeconds = maxSeconds;
                }
                
                int ticks = (int) Math.round(durationSeconds * 20);
                PotionEffect potionEffect = new PotionEffect(type, ticks, level - 1);
                effects.add(potionEffect);
            } catch (NumberFormatException e) {
                LoggerUtil.warning("Failed to parse effect '" + k + "' in " + sourceName + " potion '" + potionKey + "': " + e.getMessage());
            }
        }
        return effects;
    }

    /**
     * 读取枚举到字符串的映射配置。
     * <p>
     * 自动将配置键名转换为大写以匹配枚举常量。如果遇到未知的枚举值，会记录警告并跳过。
     * 对于 RegenConfig，会验证时间是否超过药水总时间。
     * 
     * @param sec 配置节对象
     * @param enumClass 枚举类型的Class对象
     * @param potionKey 药水键名，用于错误日志
     * @param sourceName 源文件名，用于错误日志
     * @param time 药水总时间（秒），用于验证 regen 时间
     * @param <E> 枚举类型参数
     * @return 枚举到字符串的EnumMap
     */
    private static <E extends Enum<E>> EnumMap<E, String> readEnumStringMap(ConfigurationSection sec, Class<E> enumClass, String potionKey, double time, String sourceName) {
        EnumMap<E, String> map = new EnumMap<>(enumClass);
        if (sec == null) return map;
        
        String string = String.valueOf(time);
        double maxSeconds = time > 0 ? time : Double.MAX_VALUE;
        boolean isRegen = enumClass == RegenType.class;
        
        for (String k : sec.getKeys(false)) {
            E e = parseEnum(enumClass, k);
            if (e == null) {
                LoggerUtil.warning("Unknown key '" + k + "' in " + sourceName + " potion '" + potionKey + "' section '" + sec.getName() + "'");
                continue;
            }
            
            String value = sec.getString(k, "").replace("{time}", string);
            
            if (isRegen && value.contains(ConfigManager.getSplit())) {
                String[] parts = value.split(java.util.regex.Pattern.quote(ConfigManager.getSplit()), 3);
                if (parts.length == 3) {
                    try {
                        double durationSeconds = Double.parseDouble(parts[1]);
                        if (durationSeconds > maxSeconds) {
                            LoggerUtil.warning("Regen duration exceeds potion time for '" + k + "' in " + sourceName + " potion '" + potionKey + "': " + durationSeconds + "s > " + maxSeconds + "s, clamped");
                            value = parts[0] + ConfigManager.getSplit() + maxSeconds + ConfigManager.getSplit() + parts[2];
                        }
                    } catch (NumberFormatException ignored) {
                    }
                }
            }
            
            map.put(e, value);
        }
        return map;
    }

    /**
     * 读取枚举到布尔值的映射配置。
     * <p>
     * 自动将配置键名转换为大写以匹配枚举常量。如果遇到未知的枚举值，会记录警告并跳过。
     * 
     * @param sec 配置节对象
     * @param enumClass 枚举类型的Class对象
     * @param potionKey 药水键名，用于错误日志
     * @param sourceName 源文件名，用于错误日志
     * @param <E> 枚举类型参数
     * @return 枚举到布尔值的EnumMap
     */
    private static <E extends Enum<E>> EnumMap<E, Boolean> readEnumBooleanMap(ConfigurationSection sec, Class<E> enumClass, String potionKey, String sourceName) {
        EnumMap<E, Boolean> map = new EnumMap<>(enumClass);
        if (sec == null) return map;
        for (String k : sec.getKeys(false)) {
            E e = parseEnum(enumClass, k);
            if (e == null) {
                LoggerUtil.warning("Unknown key '" + k + "' in " + sourceName + " potion '" + potionKey + "' section '" + sec.getName() + "'");
                continue;
            }
            map.put(e, sec.getBoolean(k, false));
        }
        return map;
    }

    /**
     * 读取枚举到字符串列表的映射配置。
     * <p>
     * 自动将配置键名转换为大写以匹配枚举常量。如果遇到未知的枚举值，会记录警告并跳过。
     * 
     * @param sec 配置节对象
     * @param enumClass 枚举类型的Class对象
     * @param potionKey 药水键名，用于错误日志
     * @param sourceName 源文件名，用于错误日志
     * @param <E> 枚举类型参数
     * @return 枚举到字符串列表的EnumMap
     */
    private static <E extends Enum<E>> EnumMap<E, List<String>> readEnumListMap(ConfigurationSection sec, Class<E> enumClass, String potionKey, String sourceName) {
        EnumMap<E, List<String>> map = new EnumMap<>(enumClass);
        if (sec == null) return map;
        for (String k : sec.getKeys(false)) {
            E e = parseEnum(enumClass, k);
            if (e == null) {
                LoggerUtil.warning("Unknown key '" + k + "' in " + sourceName + " potion '" + potionKey + "' section '" + sec.getName() + "'");
                continue;
            }
            map.put(e, sec.getStringList(k));
        }
        return map;
    }

    /**
     * 将字符串解析为指定的枚举类型。
     * <p>
     * 支持大小写不敏感的枚举匹配，会自动去除首尾空格并转换为大写。
     * 
     * @param enumClass 目标枚举类型的Class对象
     * @param rawKey 原始的字符串键名
     * @param <E> 枚举类型参数
     * @return 对应的枚举常量，如果解析失败则返回null
     */
    private static <E extends Enum<E>> E parseEnum(Class<E> enumClass, String rawKey) {
        if (rawKey == null) return null;
        String k = rawKey.trim();
        if (k.isEmpty()) return null;

        // 支持大小写不敏感的枚举匹配
        k = k.toUpperCase(Locale.ROOT);
        try {
            return Enum.valueOf(enumClass, k);
        } catch (IllegalArgumentException ignored) {
            return null;
        }
    }
}
