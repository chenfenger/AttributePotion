package me.chenfeng.attributepotion.hook;

import lombok.NonNull;
import me.chenfeng.attributepotion.AttributePotion;
import me.chenfeng.attributepotion.data.ActivePotion;
import me.chenfeng.attributepotion.data.PlayerProfile;
import me.chenfeng.attributepotion.data.PotionConfig;
import me.chenfeng.attributepotion.manager.ConfigManager;
import me.chenfeng.attributepotion.manager.PlayerManager;
import me.chenfeng.attributepotion.manager.constructor.OptionKey;
import me.chenfeng.attributepotion.manager.constructor.RegenConfig;
import me.chenfeng.attributepotion.manager.constructor.RegenType;
import me.chenfeng.attributepotion.utils.ExpressionUtil;
import me.clip.placeholderapi.PlaceholderAPI;
import me.clip.placeholderapi.expansion.PlaceholderExpansion;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * PlaceholderAPI 占位符扩展与工具类。
 */
public class PAPIHook extends PlaceholderExpansion {
    private static PAPIHook instance;

    /**
     * 获取当前 PAPI 扩展实例。
     *
     * @return 扩展实例，未注册时返回 null
     */
    @Nullable
    public static PAPIHook getInstance() {
        return instance;
    }

    /**
     * 创建并注册 AttributePotion 的 PlaceholderAPI 扩展。
     */
    public PAPIHook() {
        instance = this;
        this.register();
    }

    @NotNull
    @Override
    public String getIdentifier() {
        return "apn";
    }

    @NotNull
    @Override
    public String getAuthor() {
        return "chenfeng";
    }

    @NotNull
    @Override
    public String getVersion() {
        return AttributePotion.getInstance().getDescription().getVersion();
    }

    @Override
    public boolean persist() {
        return true;
    }

    @Override
    public boolean canRegister() {
        return true;
    }

    /**
     * 处理 AttributePotion 的占位符请求。
     *
     * @param player 玩家
     * @param identifier 去掉 %apn_ 和 % 后的占位符内容
     * @return 占位符结果，无法识别时返回 null
     */
    @Override
    public String onPlaceholderRequest(Player player, @NotNull String identifier) {
        if (player == null || identifier.isEmpty()) {
            return "";
        }

        PlayerProfile profile = PlayerManager.getProfile(player);
        String[] split = identifier.split("_", 2);
        if (split.length == 1) {
            return parseGlobalPlaceholder(profile, split[0]);
        }

        String type = split[0];
        String key = split[1];
        if (ConfigManager.hasGroupCooldown(key)) {
            return parseGroupPlaceholder(profile, type, key);
        }

        PotionConfig config = ConfigManager.getPotionConfig(key);
        if (config == null) {
            return "";
        }
        return parsePotionPlaceholder(profile, type, key, config);
    }

    /**
     * 处理不带药水节点的全局占位符。
     */
    private String parseGlobalPlaceholder(PlayerProfile profile, String type) {
        Map<String, ActivePotion> activePotions = profile == null
                ? Collections.emptyMap()
                : profile.getActivePotions();

        switch (type.toLowerCase(Locale.ROOT)) {
            case "used-potion":
                return String.join(",", activePotions.keySet());
            case "used-amount":
                return String.valueOf(activePotions.size());
            default:
                return null;
        }
    }

    /**
     * 处理药水组相关占位符。
     */
    private String parseGroupPlaceholder(PlayerProfile profile, String type, String group) {
        switch (type.toLowerCase(Locale.ROOT)) {
            case "remain":
                return profile == null ? "0" : formatTime(profile.getGroupCooldownRemaining(group));
            case "cooldown":
                return String.valueOf(ConfigManager.getGroupCooldown(group));
            default:
                return "";
        }
    }

    /**
     * 处理单个药水节点相关占位符。
     */
    private String parsePotionPlaceholder(PlayerProfile profile, String type, String key, PotionConfig config) {
        ActivePotion activePotion = profile == null ? null : profile.getActivePotions().get(key);
        String group = config.getGroup();

        switch (type.toLowerCase(Locale.ROOT)) {
            case "id":
                return config.getMatch();
            case "group":
                return group;
            case "time":
                return String.valueOf(config.getTime());
            case "cooldown":
                return String.valueOf(config.getCooldown());
            case "distance":
                return String.valueOf(config.getDistance());
            case "health-value":
                return regenValue(config, RegenType.HEALTH, RegenField.VALUE);
            case "health-time":
                return regenValue(config, RegenType.HEALTH, RegenField.TIME);
            case "health-mode":
                return regenValue(config, RegenType.HEALTH, RegenField.MODE);
            case "mana-value":
                return regenValue(config, RegenType.MANA, RegenField.VALUE);
            case "mana-time":
                return regenValue(config, RegenType.MANA, RegenField.TIME);
            case "mana-mode":
                return regenValue(config, RegenType.MANA, RegenField.MODE);
            case "hunger-value":
                return regenValue(config, RegenType.HUNGER, RegenField.VALUE);
            case "hunger-time":
                return regenValue(config, RegenType.HUNGER, RegenField.TIME);
            case "hunger-mode":
                return regenValue(config, RegenType.HUNGER, RegenField.MODE);
            case "consume":
                return optionValue(config, OptionKey.CONSUME, true);
            case "cover":
                return optionValue(config, OptionKey.COVER, false);
            case "shift":
                return optionValue(config, OptionKey.SHIFT, false);
            case "cool":
                return optionValue(config, OptionKey.COOL, true);
            case "death":
                return optionValue(config, OptionKey.DEATH, false);
            case "quit":
                return optionValue(config, OptionKey.QUIT, false);
            case "range":
                return optionValue(config, OptionKey.RANGE, false);
            case "stats":
                return String.valueOf(activePotion != null);
            case "remain":
                return profile == null ? "0" : formatTime(profile.getPotionCooldownRemaining(key));
            case "duration":
                return activePotion == null ? "0" : formatTime(activePotion.getRemainingSeconds());
            case "attr":
                return activePotion == null ? "" : String.join(",\n", activePotion.getAppliedAttributes());
            default:
                return null;
        }
    }

    /**
     * 获取药水恢复配置的指定字段。
     */
    private String regenValue(PotionConfig config, RegenType type, RegenField field) {
        RegenConfig.RegenData data = config.getRegen().getData(type);
        if (data == null) {
            return "0";
        }

        switch (field) {
            case VALUE:
                return String.valueOf(data.getAmount());
            case TIME:
                return String.valueOf(data.getDuration());
            case MODE:
                return String.valueOf(data.getMode());
            default:
                return "0";
        }
    }

    /**
     * 获取药水 optional 配置值。
     */
    private String optionValue(PotionConfig config, OptionKey key, boolean def) {
        return String.valueOf(config.getOptional().isEnabled(key, def));
    }

    /**
     * 统一格式化时间输出。
     */
    private String formatTime(double seconds) {
        if (seconds < 0) {
            return "-1";
        }
        return seconds <= 0 ? "0" : String.format(Locale.US, "%.1f", seconds);
    }

    /**
     * 替换单个字符串中的 PlaceholderAPI 占位符。
     *
     * @param player 玩家
     * @param placeholder 包含占位符的字符串
     * @return 替换后的字符串；PlaceholderAPI 未注册时返回原始字符串
     */
    public static String replacePlaceholders(@NonNull Player player, @NotNull String placeholder) {
        if (instance == null) {
            return placeholder;
        }
        return PlaceholderAPI.setPlaceholders(player, placeholder);
    }

    /**
     * 批量替换字符串列表中的 PlaceholderAPI 占位符。
     *
     * @param player 玩家
     * @param list 包含占位符的字符串列表
     * @return 替换后的字符串列表；PlaceholderAPI 未注册时返回原始列表
     */
    public static List<String> replacePlaceholders(@NonNull Player player, @NotNull List<String> list) {
        if (instance == null) {
            return list;
        }

        if (list.isEmpty()) {
            return Collections.emptyList();
        }

        return PlaceholderAPI.setPlaceholders(player, list);
    }

    /**
     * 替换占位符后计算数学表达式。
     *
     * @param player 玩家
     * @param expression 数学表达式
     * @return 计算结果
     */
    public static double evaluateExpression(@NonNull Player player, @NotNull String expression) {
        String replaced = replacePlaceholders(player, expression);
        return ExpressionUtil.eval(replaced);
    }

    /**
     * 替换占位符后计算数学表达式并返回字符串。
     *
     * @param player 玩家
     * @param expression 数学表达式
     * @return 计算结果字符串
     */
    public static String evaluateExpressionToString(@NonNull Player player, @NotNull String expression) {
        return String.valueOf(evaluateExpression(player, expression));
    }

    /**
     * 恢复配置字段类型。
     */
    private enum RegenField {
        VALUE,
        TIME,
        MODE
    }
}
