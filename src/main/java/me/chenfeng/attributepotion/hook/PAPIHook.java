package me.chenfeng.attributepotion.hook;

import lombok.NonNull;
import me.chenfeng.attributepotion.utils.ExpressionUtil;
import me.clip.placeholderapi.PlaceholderAPI;
import me.clip.placeholderapi.expansion.PlaceholderExpansion;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.Collections;
import java.util.List;
import java.util.stream.Collectors;

/**
 * PlaceholderAPI 占位符扩展与工具类。
 * <p>
 * 提供占位符解析功能，支持将包含占位符的字符串或列表转换为实际值。
 * 同时提供表达式计算功能，可将占位符替换后的结果进行数学运算。
 */
public class PAPIHook extends PlaceholderExpansion {
    private static PAPIHook instance;

    /**
     * 获取 PAPIHook 实例。
     * 
     * @return PAPIHook 实例，如果未初始化则返回 null
     */
    @Nullable
    public static PAPIHook getInstance() {
        return instance;
    }

    /**
     * 构造并自动注册占位符扩展。
     * <p>
     * 注意：应在插件 onEnable 阶段调用此构造函数，确保 PlaceholderAPI 已加载。
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
        return "1.0.0";
    }

    @Override
    public boolean persist() {
        return true;
    }

    @Override
    public String onPlaceholderRequest(Player player, @NotNull String identifier) {
        if (identifier.isEmpty()) {
            return "";
        }

        // TODO: 在此处添加自定义占位符处理逻辑
        // 例如：if (identifier.startsWith("cooldown_")) { ... }

        return null;
    }

    /**
     * 处理单个字符串中的 PlaceholderAPI 占位符替换。
     * <p>
     * 该方法将包含占位符的字符串转换为实际的值。
     * 如果 PlaceholderAPI 未安装，则返回原始字符串。
     * 
     * @param player 玩家对象，用于获取玩家相关的占位符值
     * @param placeholder 包含占位符的字符串（例如 "%apn_example%"）
     * @return 替换后的字符串，如果 PlaceholderAPI 未安装或占位符无效则返回原字符串
     */
    public static String replacePlaceholders(@NonNull Player player, @NotNull String placeholder) {
        if (instance == null) {
            return placeholder;
        }
        return PlaceholderAPI.setPlaceholders(player, placeholder);
    }

    /**
     * 批量处理字符串列表中的 PlaceholderAPI 占位符替换。
     * <p>
     * 该方法将列表中每个字符串包含的占位符全部转换为实际的值。
     * 如果 PlaceholderAPI 未安装，则返回原始列表。
     * 
     * @param player 玩家对象，用于获取玩家相关的占位符值
     * @param list 包含占位符的字符串列表（例如 ["%apn_example1%", "%apn_example2%"]）
     * @return 替换后的字符串列表，如果 PlaceholderAPI 未安装则返回原列表
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
     * 替换占位符后计算表达式的数值结果。
     * <p>
     * 先将字符串中的占位符替换为实际值，然后使用表达式引擎计算结果。
     * 适用于需要动态计算的场景，例如："100 + %player_level% * 10"。
     * 
     * @param player 玩家对象，用于获取玩家相关的占位符值
     * @param expression 包含占位符的数学表达式字符串
     * @return 计算结果（double 类型），如果表达式无效则抛出异常
     * @throws IllegalArgumentException 当表达式格式错误或计算失败时
     */
    public static double evaluateExpression(@NonNull Player player, @NotNull String expression) {
        String replaced = replacePlaceholders(player, expression);
        return ExpressionUtil.eval(replaced);
    }

    /**
     * 替换占位符后计算表达式的字符串形式结果。
     * <p>
     * 先将字符串中的占位符替换为实际值，然后使用表达式引擎计算并将结果转为字符串。
     * 
     * @param player 玩家对象，用于获取玩家相关的占位符值
     * @param expression 包含占位符的数学表达式字符串
     * @return 计算结果的字符串表示
     */
    public static String evaluateExpressionToString(@NonNull Player player, @NotNull String expression) {
        return String.valueOf(evaluateExpression(player, expression));
    }
}
