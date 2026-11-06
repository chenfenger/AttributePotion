package me.chenfeng.attributepotion.manager;

import lombok.Getter;
import lombok.NonNull;
import me.chenfeng.attributepotion.AttributePotion;
import me.chenfeng.attributepotion.data.PotionConfig;
import me.chenfeng.attributepotion.enums.MatchMode;
import me.chenfeng.attributepotion.handler.PotionHandler;
import me.chenfeng.attributepotion.listener.hook.DragonCoreListener;
import me.chenfeng.attributepotion.manager.constructor.PotionDataLoader;
import me.chenfeng.attributepotion.utils.LoggerUtil;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;

import java.io.File;
import java.util.*;

public class ConfigManager {
    @Getter
    private static boolean debug = false;
    @Getter
    private static int loadDelay = 60;
    @Getter
    private static String attributePlugin;
    @Getter
    private static MatchMode matchMode = MatchMode.NAME;
    @Getter
    private static String nbtKey = "apn";
    @Getter
    private static String nbtCountKey = "";
    @Getter
    private static boolean nbtApiAvailable = false;
    @Getter
    private static boolean fixedGroupCooldown = true;
    @Getter
    private static boolean anvilProtect = true;
    @Getter
    private static boolean runEndOnOfflineExpire = true;
    @Getter
    private static int autoSaveInterval = 300;
    @Getter
    private static boolean contain = true;
    @Getter
    private static String split = "<->";
    private static long messageCooldown = 500;
    @Getter
    private static String bossBarTitle = "%potion% 剩余 %remaining% 秒";
    @Getter
    private static String bossBarColor = "BLUE";
    @Getter
    private static String bossBarStyle = "SOLID";
    @Getter
    private static int bossBarUpdateInterval = 20;

    private static Map<String, String> keySlot = Collections.emptyMap();

    private static Set<String> consumableTypes = Collections.emptySet();

    private static Map<String, Double> groupCooldownMap = Collections.emptyMap();

    private static Map<String, String> messages = Collections.emptyMap();
    private static final Map<UUID, Map<String, Long>> messageCooldownMap = new HashMap<>();
    /**
     * 对外读取的药水表。重载时先在 loadingPotions 里建好再整体替换，异步读取方不会看到半张表。
     */
    private static volatile Map<String, PotionConfig> potionConfigMap = Collections.emptyMap();
    private static Map<String, PotionConfig> loadingPotions = new LinkedHashMap<>();

    @Getter
    private static YamlConfiguration hudYaml;
    /**
     * 清除药水配置缓存，触发重新排序
     */
    public static void clearPotionCache() {
        PotionHandler.clearCache();
    }

    /**
     * 加载配置文件中的所有配置项
     * 该方法会重新加载配置文件并更新所有相关的配置变量
     */
    public static synchronized void loadConfig() {
        AttributePotion plugin = AttributePotion.getInstance();
        plugin.reloadConfig();
        FileConfiguration config = plugin.getConfig();

        File file = new File(plugin.getDataFolder(), "press-hud.yml");
        if(!file.exists()) {
            plugin.saveResource("press-hud.yml", false);
        }
        hudYaml = YamlConfiguration.loadConfiguration(file);

        DragonCoreListener.sendYaml();

        debug = config.getBoolean("debug", false);
        contain = config.getBoolean("contain", true);
        loadDelay = config.getInt("load-delay", 60);
        attributePlugin = config.getString("attribute-plugin", "");
        nbtApiAvailable = isPluginLoaded("NBTAPI") || isPluginLoaded("NBT-API");
        
        String matchModeStr = config.getString("match-mode", "name");
        if(matchModeStr.equalsIgnoreCase("nbt")) {
            if(!nbtApiAvailable) {
                matchMode = MatchMode.NAME;
                LoggerUtil.warning("[AttributePotion] 未安装 NBT-API，请安装该插件以使用 NBT 模式，已重置为 NAME");
            } else {
                matchMode = MatchMode.NBT;
            }
        } else {
            try {
                matchMode = MatchMode.valueOf(matchModeStr.toUpperCase());
            } catch (IllegalArgumentException e) {
                matchMode = MatchMode.NAME;
                LoggerUtil.warning("[AttributePotion] 无效的 match-mode 配置: " + matchModeStr + "，已重置为 NAME");
            }
        }
        
        nbtKey = config.getString("nbt-key", "apn");
        nbtCountKey = config.getString("nbt-count", "");
        split =  config.getString("split", "<->");
        keySlot = readStringMap(config, "key-slots");
        
        // 加载消耗品类型列表
        Set<String> consumables = new HashSet<>();
        for (String type : config.getStringList("consumable-types")) {
            consumables.add(type.trim().toUpperCase(Locale.ROOT));
        }
        consumableTypes = consumables;
        
        fixedGroupCooldown = config.getBoolean("fixed-group-cooldown", true);
        anvilProtect = config.getBoolean("anvil-protect", true);
        runEndOnOfflineExpire = config.getBoolean("run-end-on-offline-expire", true);
        autoSaveInterval = config.getInt("auto-save-interval", 300);
        groupCooldownMap = readDoubleMap(config, "group-cooldowns");
        messageCooldown = config.getLong("message-cooldown", 500);
        bossBarTitle = config.getString("bossbar.title", "%potion% 剩余 %remaining% 秒").replace("&", "§");
        bossBarColor = config.getString("bossbar.color", "BLUE");
        bossBarStyle = config.getString("bossbar.style", "SOLID");
        bossBarUpdateInterval = Math.max(1, config.getInt("bossbar.update-interval", 20));
        messages = readStringMap(config, "messages");

        loadingPotions = new LinkedHashMap<>();

        int loaded = loadPotionFolder(new File(plugin.getDataFolder(), "potion"));
        File legacyFolder = new File(plugin.getDataFolder(), "potions");
        if (legacyFolder.exists() && legacyFolder.isDirectory()) {
            loaded += loadPotionFolder(legacyFolder);
        }

        if (loaded == 0) {
            LoggerUtil.warning("[AttributePotion] No potion yml files found. Check the potion folder.");
        }

        potionConfigMap = Collections.unmodifiableMap(loadingPotions);
        clearPotionCache();
        warnUnknownGroups();
    }

    /**
     * fixed-group-cooldown 开启时，组冷却完全取自 group-cooldowns。药水写了组却没配冷却，组冷却就不会生效，这里提示出来。
     */
    private static void warnUnknownGroups() {
        if (!fixedGroupCooldown) {
            return;
        }
        for (PotionConfig potion : potionConfigMap.values()) {
            String group = potion.getGroup();
            if (group != null && !group.isEmpty() && !groupCooldownMap.containsKey(group)) {
                LoggerUtil.warning("[AttributePotion] 药水 " + potion.getKey() + " 所属的组 " + group
                        + " 没有写在 group-cooldowns 里，该组不会有组冷却");
            }
        }
    }

    private static boolean isPluginLoaded(String pluginName) {
        return Bukkit.getPluginManager().getPlugin(pluginName) != null;
    }

    /**
     * 读取配置中string-string结构的map
     * @param config 配置文件对象
     * @param path 键名
     * @return map
     */
    private static Map<String, String> readStringMap(FileConfiguration config, String path) {
        ConfigurationSection section = config.getConfigurationSection(path);

        // 如果配置节不存在，返回空映射
        if (section == null) {
            return Collections.emptyMap();
        }
        
        Map<String, String> result = new LinkedHashMap<>();
        // 遍历配置节中的所有键值对
        for (String key : section.getKeys(false)) {
            result.put(key, section.getString(key, "").replace("&", "§"));
        }
        return Collections.unmodifiableMap(result);
    }

    /**
     * 读取配置中string-double结构的map
     * @param config 配置文件对象
     * @param path 键名
     * @return map
     */
    private static Map<String, Double> readDoubleMap(FileConfiguration config, String path) {
        ConfigurationSection section = config.getConfigurationSection(path);
        if (section == null) {
            return Collections.emptyMap();
        }
        Map<String, Double> result = new LinkedHashMap<>();
        for (String key : section.getKeys(false)) {
            result.put(key, section.getDouble(key, 0));
        }
        return Collections.unmodifiableMap(result);
    }

    /**
     * 存储药水数据到映射表中
     * 
     * @param key 药水数据的键名
     * @param data 要存储的药水数据对象
     */
    public static void putPotionConfig(String key, PotionConfig data) {
        if (loadingPotions.containsKey(key)) {
            LoggerUtil.warning("[AttributePotion] Duplicate potion key '" + key + "' will be overwritten");
        }
        loadingPotions.put(key, data);
    }

    /**
     * 获取指定药水组的冷却时间。
     * 
     * @param group 药水组名称
     * @return 药水组的冷却时间（秒），如果未配置则返回 0.0
     */
    public static double getGroupCooldown(@NonNull String group) {
        return groupCooldownMap.getOrDefault(group, 0.0);
    }

    /**
     * 判断指定药水组是否配置了独立冷却。
     *
     * @param group 药水组名
     * @return 已配置时返回 true
     */
    public static boolean hasGroupCooldown(@NonNull String group) {
        return groupCooldownMap.containsKey(group);
    }

    /**
     * 根据键名获取药水数据
     * 
     * @param key 药水数据的键名
     * @return 对应的药水数据对象，如果不存在则返回null
     */
    public static PotionConfig getPotionConfig(@NonNull String key) {
        return potionConfigMap.get(key);
    }

    /**
     * 获取所有药水数据的映射表
     * 
     * @return 包含所有药水数据的不可变映射
     */
    public static Map<String, PotionConfig> getAllPotionData() {
        return potionConfigMap;
    }

    /**
     * 判断材料是否配置为原版消耗后才生效（consumable-types）。
     *
     * @param material 材料
     * @return 配置过返回 true
     */
    public static boolean isConsumableType(Material material) {
        return consumableTypes.contains(material.name());
    }


    /**
     * 获取所有需要注册的按键键名集合。
     * <p>
     * 返回的集合包含所有在 config.yml 中 key-slots 配置节定义的按键标识符，
     * 例如 "Z"、"X"、"C" 等，可用于遍历或验证按键配置是否存在。
     * 
     * @return 按键槽位的键名集合，如果未配置则返回空集合
     */
    public static Set<String> getRegisterKeys() {
        if(keySlot == null) {
            return Collections.emptySet();
        }
        return keySlot.keySet();
    }

    /**
     * 根据按键标识符获取对应的槽位名称。
     * <p>
     * 从 config.yml 的 key-slots 配置中查找指定按键（如 "Z"、"X"、"C"）
     * 所绑定的槽位名称（如 "额外槽位1"）。
     * 
     * @param key 按键标识符（例如 "Z"、"X"、"C"）
     * @return 对应的槽位名称，如果未配置该按键则返回 null
     */
    public static String getSlot(@NotNull String key) {
        return keySlot.get(key);
    }

    public static String getMessage(String key, String def) {
        String message = messages.get(key);
        return message == null || message.isEmpty() ? def : message;
    }

    /**
     * 递归查找指定文件夹下所有的.yml文件并加载
     * 
     * @param folder 要搜索的文件夹路径
     */
    private static int loadPotionFolder(File folder) {
        if (!folder.exists() || !folder.isDirectory()) {
            return 0;
        }

        return findAllYmlFiles(folder);
    }

    private static int findAllYmlFiles(File folder) {
        File[] files = folder.listFiles();
        int loaded = 0;
        if (files != null) {
            for (File file : files) {
                // 如果是目录则递归搜索
                if (file.isDirectory()) {
                    loaded += findAllYmlFiles(file);
                } else if (file.getName().endsWith(".yml")) {
                    // 加载.yml文件
                    PotionDataLoader.load(file);
                    loaded++;
                }
            }
        }
        return loaded;
    }

    /**
     * 向玩家发送配置的消息，带有冷却时间限制和占位符替换功能。
     * 
     * @param player 接收消息的玩家对象
     * @param key 消息键名，用于从messages配置中获取对应的消息内容
     * @param replacements 占位符替换映射，键为占位符（如 "%name%"），值为替换内容
     */
    public static void sendMessage(Player player, String key, Map<String, String> replacements) {
        String string = messages.get(key);
        if(string == null || string.isEmpty()) {
            return;
        }

        long currentTime = System.currentTimeMillis();

        // 检查消息冷却时间
        Map<String, Long> playerCooldowns = messageCooldownMap.computeIfAbsent(player.getUniqueId(), uuid -> new HashMap<>());
        long lastTime = playerCooldowns.getOrDefault(key, 0L);
        if(currentTime - lastTime < messageCooldown) {
            return;
        }

        // 先替换调用方给的占位符：范围药水的 %player% 指使用者，不能被接收者自己的名字抢先替换
        if(replacements != null && !replacements.isEmpty()) {
            for (Map.Entry<String, String> entry : replacements.entrySet()) {
                if(entry.getKey() != null && entry.getValue() != null) {
                    string = string.replace(entry.getKey(), entry.getValue());
                }
            }
        }
        string = string.replace("%player%", player.getName())
                .replace("%player_name%", player.getName());

        // 更新最后发送时间
        playerCooldowns.put(key, currentTime);

        // 发送消息
        player.sendMessage(string);
    }

    /**
     * 清理玩家的消息冷却记录，在玩家退出时调用，否则记录会一直累积。
     *
     * @param uuid 玩家 UUID
     */
    public static void clearMessageCooldown(UUID uuid) {
        messageCooldownMap.remove(uuid);
    }
}
