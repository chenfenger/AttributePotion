package me.chenfeng.attributepotion.handler;

import de.tr7zw.nbtapi.NBT;
import me.chenfeng.attributepotion.AttributePotion;
import me.chenfeng.attributepotion.api.event.PotionApplyEvent;
import me.chenfeng.attributepotion.api.event.PotionUseEvent;
import me.chenfeng.attributepotion.data.ActivePotion;
import me.chenfeng.attributepotion.data.PlayerProfile;
import me.chenfeng.attributepotion.data.PotionConfig;
import me.chenfeng.attributepotion.hook.PAPIHook;
import me.chenfeng.attributepotion.hook.attribute.AbstractAttributeHook;
import me.chenfeng.attributepotion.hook.mana.ManaHook;
import me.chenfeng.attributepotion.manager.ConfigManager;
import me.chenfeng.attributepotion.manager.PlayerManager;
import me.chenfeng.attributepotion.manager.constructor.CommandPhase;
import me.chenfeng.attributepotion.manager.constructor.OptionKey;
import me.chenfeng.attributepotion.manager.constructor.OptionalConfig;
import me.chenfeng.attributepotion.manager.constructor.RegenConfig;
import me.chenfeng.attributepotion.manager.constructor.RegenType;
import me.chenfeng.attributepotion.manager.constructor.TriggerType;
import me.chenfeng.attributepotion.utils.ExpressionUtil;
import me.chenfeng.attributepotion.utils.ItemUtil;
import me.chenfeng.attributepotion.utils.LoggerUtil;
import org.bukkit.Bukkit;
import org.bukkit.attribute.Attribute;
import org.bukkit.attribute.AttributeInstance;
import org.bukkit.boss.BarColor;
import org.bukkit.boss.BarStyle;
import org.bukkit.boss.BossBar;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.potion.PotionEffect;
import org.bukkit.scheduler.BukkitRunnable;
import org.bukkit.scheduler.BukkitTask;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Pattern;
import java.util.stream.Collectors;


public class PotionHandler {

    /**
     * 属性数值保留的小数位。属性常用 0.05 这样的小数，不能和界面显示一样只留一位。
     */
    private static final int ATTRIBUTE_SCALE = 4;

    private static volatile MatchIndex matchIndex;
    private static final Attribute MAX_HEALTH_ATTRIBUTE = resolveMaxHealthAttribute();

    /**
     * 清理药水匹配缓存，通常在配置重载后调用。
     */
    public static void clearCache() {
        matchIndex = null;
    }

    /**
     * 匹配物品并按完整流程使用药水。
     *
     * @param player 使用药水的玩家
     * @param itemStack 要使用的物品
     * @return 是否成功使用
     */
    public static boolean usePotion(Player player, ItemStack itemStack) {
        return usePotion(player, itemStack, matchPotion(itemStack), null);
    }

    /**
     * 按指定触发来源匹配并使用物品药水。
     */
    public static boolean usePotion(Player player, ItemStack itemStack, TriggerType triggerType) {
        return usePotion(player, itemStack, matchPotion(itemStack), triggerType);
    }

    /**
     * 使用已经匹配好的药水配置，避免重复执行物品匹配。
     */
    public static boolean usePotion(Player player, ItemStack itemStack, PotionConfig potionConfig, TriggerType triggerType) {
        if (potionConfig == null || !allowsTrigger(potionConfig, triggerType)) {
            return false;
        }

        boolean used = usePotion(player, potionConfig.getKey());
        if (used) {
            applyItemCooldown(player, itemStack, potionConfig);
        }
        return used;
    }

    /**
     * 按节点使用药水。依次检查数据加载、可选条件、冷却、使用条件和使用事件，全部通过后应用效果。
     *
     * @param player 使用药水的玩家
     * @param potionKey 药水节点
     * @return 是否使用成功
     */
    public static boolean usePotion(Player player, String potionKey) {
        long start = debugStart();
        PotionConfig potionConfig = ConfigManager.getPotionConfig(potionKey);
        if (potionConfig == null) {
            return false;
        }

        PlayerProfile profile = PlayerManager.getOrCreateProfile(player);
        boolean applied = passesChecks(player, profile, potionConfig)
                && !PotionUseEvent.callEvent(player, potionConfig)
                && applyPotionEffects(player, profile, potionConfig, true);

        if (start != 0L) {
            debugTiming("usePotion(" + potionKey + "): result=" + applied, start);
        }
        return applied;
    }

    /**
     * 直接应用药水，跳过可选条件、冷却、使用条件和使用事件。
     */
    public static boolean forceUsePotion(Player player, String potionKey) {
        PotionConfig potionConfig = ConfigManager.getPotionConfig(potionKey);
        if (potionConfig == null) {
            return false;
        }
        return applyPotionEffects(player, PlayerManager.getOrCreateProfile(player), potionConfig, true);
    }

    /**
     * 预检查玩家当前是否可以使用指定药水。
     * <p>
     * 只做条件判断，不触发使用事件，也不应用效果。按键蓄力开始前用它决定是否显示进度。
     *
     * @param player 玩家
     * @param potionConfig 药水配置
     * @param triggerType 触发类型
     * @return 当前可以使用返回 true
     */
    public static boolean canUsePotion(Player player, PotionConfig potionConfig, TriggerType triggerType) {
        if (player == null || potionConfig == null || !allowsTrigger(potionConfig, triggerType)) {
            return false;
        }
        return passesChecks(player, PlayerManager.getOrCreateProfile(player), potionConfig);
    }

    private static boolean passesChecks(Player player, PlayerProfile profile, PotionConfig config) {
        if (!profile.isDataLoaded()) {
            ConfigManager.sendMessage(player, "data-loading", null);
            return false;
        }
        return checkOptionalConditions(player, profile, config)
                && checkCooldowns(player, profile, config)
                && checkConditions(player, config);
    }

    /**
     * 增加或减少已激活非永久药水的剩余时间。
     */
    public static boolean addPotionTime(Player player, String potionKey, double seconds) {
        if (seconds == 0 || Double.isNaN(seconds) || Double.isInfinite(seconds)) {
            return false;
        }

        PlayerProfile profile = PlayerManager.getProfile(player);
        if (profile == null) {
            return false;
        }

        ActivePotion activePotion = profile.getActivePotions().get(potionKey);
        PotionConfig config = ConfigManager.getPotionConfig(potionKey);
        if (activePotion == null || config == null || activePotion.isPermanent()) {
            return false;
        }

        activePotion.cancelAllTickTasks();
        activePotion.addTime(seconds);
        if (activePotion.isExpired()) {
            PlayerManager.removeActivePotion(player, profile, potionKey);
            return true;
        }

        applyRemainingVanillaEffects(player, config, activePotion);
        applyRegen(player, config, activePotion, activePotion.getElapsedSeconds());
        scheduleTickCommands(player, config, activePotion);
        scheduleBossBar(player, config, activePotion);
        return true;
    }

    /**
     * 判断药水是否接受指定的触发方式。triggerType 为 null 表示不限制触发方式（API、指令、原版消耗）。
     */
    public static boolean allowsTrigger(PotionConfig config, TriggerType triggerType) {
        if (triggerType == null) {
            return true;
        }
        return config.getTriggers().contains(TriggerType.ALL)
                || config.getTriggers().contains(triggerType);
    }

    /**
     * 当药水启用 COOL 选项时，应用原版物品冷却显示。
     */
    private static void applyItemCooldown(Player player, ItemStack itemStack, PotionConfig config) {
        if (!config.getOptional().isEnabled(OptionKey.COOL, false) || config.getCooldown() <= 0) {
            return;
        }
        player.setCooldown(itemStack.getType(), (int) Math.round(config.getCooldown() * 20));
    }

    private static boolean checkOptionalConditions(Player player, PlayerProfile profile, PotionConfig config) {
        OptionalConfig optional = config.getOptional();

        if (optional.isEnabled(OptionKey.SHIFT, false) && !player.isSneaking()) {
            ConfigManager.sendMessage(player, "shift", null);
            return false;
        }

        if (!optional.isEnabled(OptionKey.COVER, true) && profile.getActivePotions().containsKey(config.getKey())) {
            ConfigManager.sendMessage(player, "cover-deny", potionPlaceholders(config));
            return false;
        }

        return true;
    }

    private static boolean checkCooldowns(Player player, PlayerProfile profile, PotionConfig config) {
        if (profile.isPotionOnCooldown(config.getKey())) {
            Map<String, String> replacements = potionPlaceholders(config);
            replacements.put("%cooldown%", formatNumber(profile.getPotionCooldownRemaining(config.getKey())));
            ConfigManager.sendMessage(player, "on-potion-cooldown", replacements);
            return false;
        }

        String groupName = config.getGroup();
        if (groupName != null && !groupName.isEmpty() && profile.isGroupOnCooldown(groupName)) {
            Map<String, String> replacements = potionPlaceholders(config);
            replacements.put("%group%", groupName);
            replacements.put("%cooldown%", formatNumber(profile.getGroupCooldownRemaining(groupName)));
            ConfigManager.sendMessage(player, "on-group-cooldown", replacements);
            return false;
        }

        return true;
    }

    /**
     * 检查药水的使用条件。
     * <p>
     * 条件格式：{@code 表达式<->失败消息}，失败消息可省略。
     * 表达式解析失败时视为不满足，避免配置写错后条件被放行。
     */
    private static boolean checkConditions(Player player, PotionConfig config) {
        List<String> conditions = config.getConditions();
        if (conditions == null || conditions.isEmpty()) {
            return true;
        }

        Pattern splitPattern = Pattern.compile(Pattern.quote(ConfigManager.getSplit()));
        for (String condition : conditions) {
            if (condition == null || condition.isEmpty()) {
                continue;
            }

            String[] parts = splitPattern.split(condition, 2);
            String expression = parts[0].trim();
            String failMessage = parts.length == 2 ? parts[1].trim() : "";

            boolean passed;
            try {
                passed = ExpressionUtil.check(PAPIHook.replacePlaceholders(player, expression));
            } catch (RuntimeException e) {
                LoggerUtil.warning("[AttributePotion] 药水 " + config.getKey() + " 的条件表达式无法解析，已按不满足处理: "
                        + expression + "（" + e.getMessage() + "）");
                passed = false;
            }

            if (!passed) {
                if (!failMessage.isEmpty()) {
                    player.sendMessage(failMessage.replace("%id%", config.getKey()));
                }
                ConfigManager.sendMessage(player, "use-deny", potionPlaceholders(config));
                return false;
            }
        }

        return true;
    }

    /**
     * 应用药水效果。
     *
     * @param primary true 表示玩家自己使用：设置冷却、发送使用消息并扩散范围效果；
     *                false 表示被范围效果波及：不占用该玩家的冷却
     */
    private static boolean applyPotionEffects(Player player, PlayerProfile profile, PotionConfig config, boolean primary) {
        PlayerManager.removeActivePotion(player, profile, config.getKey());

        long durationMillis = config.getTime() < 0 ? -1 : (long) (config.getTime() * 1000);
        ActivePotion activePotion = new ActivePotion(config.getKey(), player, durationMillis);
        profile.addActivePotion(config.getKey(), activePotion);

        if (primary) {
            profile.setPotionCooldown(config.getKey(), config.getCooldown());
            String groupName = config.getGroup();
            if (groupName != null && !groupName.isEmpty()) {
                profile.setGroupCooldown(groupName, config.getCooldown());
            }
        }

        applyAttributes(player, config, activePotion);
        applyVanillaEffects(player, config);
        applyRegen(player, config, activePotion, 0);
        scheduleTickCommands(player, config, activePotion);
        scheduleBossBar(player, config, activePotion);

        if (primary) {
            Map<String, String> replacements = potionPlaceholders(config);
            replacements.put("%time%", formatNumber(config.getTime()));
            ConfigManager.sendMessage(player, "use-potion", replacements);
        }

        CommandHandler.executeCommands(player, config.getCommands().get(CommandPhase.SUCCESS), config.getTime());
        PotionApplyEvent.callEvent(player, config, activePotion);

        if (primary) {
            applyRangeEffects(player, config);
        }

        LoggerUtil.debug("[AttributePotion] 玩家 " + player.getName() + " 成功使用药水 " + config.getKey());
        return true;
    }

    /**
     * 玩家数据加载完成后恢复持久化保存的激活药水。
     */
    public static void restoreActivePotion(Player player, ActivePotion activePotion) {
        PotionConfig config = ConfigManager.getPotionConfig(activePotion.getPotionKey());
        if (config == null || activePotion.isExpired()) {
            return;
        }

        applyStoredAttributes(player, activePotion);
        applyRemainingVanillaEffects(player, config, activePotion);
        applyRegen(player, config, activePotion, activePotion.getElapsedSeconds());
        scheduleTickCommands(player, config, activePotion);
        scheduleBossBar(player, config, activePotion);
    }

    private static void applyStoredAttributes(Player player, ActivePotion activePotion) {
        List<String> attributes = activePotion.getAppliedAttributes();
        if (attributes == null || attributes.isEmpty()) {
            return;
        }

        AbstractAttributeHook attributeHook = AttributePotion.getInstance().getAttributeHook();
        if (attributeHook == null) {
            return;
        }

        try {
            attributeHook.addAttribute(player, activePotion.getPotionKey(), attributes);
        } catch (Exception e) {
            LoggerUtil.warning("[AttributePotion] Failed to restore attributes for potion "
                    + activePotion.getPotionKey() + ": " + e.getMessage());
        }
    }

    private static void applyRangeEffects(Player source, PotionConfig config) {
        if (!config.getOptional().isEnabled(OptionKey.RANGE, false) || config.getDistance() <= 0) {
            return;
        }

        List<String> affectedNames = new ArrayList<>();
        double distanceSquared = config.getDistance() * config.getDistance();
        for (Player target : Bukkit.getOnlinePlayers()) {
            if (target.equals(source) || target.isDead() || !target.getWorld().equals(source.getWorld())) {
                continue;
            }
            if (target.getLocation().distanceSquared(source.getLocation()) > distanceSquared) {
                continue;
            }

            PlayerProfile targetProfile = PlayerManager.getOrCreateProfile(target);
            if (!config.getOptional().isEnabled(OptionKey.COVER, true)
                    && targetProfile.getActivePotions().containsKey(config.getKey())) {
                continue;
            }

            applyPotionEffects(target, targetProfile, config, false);
            affectedNames.add(target.getName());

            Map<String, String> otherReplacements = potionPlaceholders(config);
            otherReplacements.put("%player%", source.getName());
            ConfigManager.sendMessage(target, "other-potion", otherReplacements);
        }

        if (!affectedNames.isEmpty()) {
            Map<String, String> replacements = potionPlaceholders(config);
            replacements.put("%range%", formatNumber(config.getDistance()));
            replacements.put("%playerList%", String.join(", ", affectedNames));
            ConfigManager.sendMessage(source, "near-potion", replacements);
        }
    }

    private static void applyAttributes(Player player, PotionConfig config, ActivePotion activePotion) {
        List<String> attributes = buildAttributeLines(player, config);
        if (attributes.isEmpty()) {
            return;
        }

        AbstractAttributeHook attributeHook = AttributePotion.getInstance().getAttributeHook();
        if (attributeHook == null) {
            LoggerUtil.warning("[AttributePotion] No attribute hook available for potion " + config.getKey());
            return;
        }

        try {
            attributeHook.addAttribute(player, config.getKey(), attributes);
            activePotion.addAppliedAttributes(attributes);
        } catch (Exception e) {
            LoggerUtil.warning("[AttributePotion] Failed to apply attributes for potion " + config.getKey() + ": " + e.getMessage());
        }
    }

    private static List<String> buildAttributeLines(Player player, PotionConfig config) {
        Map<String, String> configured = config.getAttributes();
        if (configured == null || configured.isEmpty()) {
            return Collections.emptyList();
        }

        List<String> attributes = new ArrayList<>();
        for (Map.Entry<String, String> entry : configured.entrySet()) {
            if (entry.getKey() == null || entry.getKey().isEmpty()) {
                continue;
            }

            String value = entry.getValue() == null ? "" : PAPIHook.replacePlaceholders(player, entry.getValue()).trim();
            if (!value.isEmpty()) {
                attributes.add(entry.getKey() + ": " + formatAttributeValue(value));
            }
        }
        return attributes;
    }

    private static String formatAttributeValue(String value) {
        try {
            return new BigDecimal(ExpressionUtil.eval(value))
                    .setScale(ATTRIBUTE_SCALE, RoundingMode.HALF_UP)
                    .stripTrailingZeros()
                    .toPlainString();
        } catch (IllegalArgumentException ignored) {
            return value;
        }
    }

    private static void applyVanillaEffects(Player player, PotionConfig config) {
        for (PotionEffect effect : config.getEffects()) {
            player.addPotionEffect(effect, true);
        }
    }

    /**
     * 按已经过去的时间补上原版效果的剩余时长，用于登录恢复、延长时长和复活。
     * <p>
     * 效果有自己的时长，不能在恢复时重新给满，否则下线再上线就能刷新效果。
     */
    public static void applyRemainingVanillaEffects(Player player, PotionConfig config, ActivePotion activePotion) {
        for (PotionEffect effect : config.getEffects()) {
            int ticks = remainingEffectTicks(effect, config, activePotion);
            if (ticks > 0) {
                player.addPotionEffect(new PotionEffect(effect.getType(), ticks, effect.getAmplifier(),
                        effect.isAmbient(), effect.hasParticles()), true);
            }
        }
    }

    private static int remainingEffectTicks(PotionEffect effect, PotionConfig config, ActivePotion activePotion) {
        // 永久药水里写 {time} 的效果，一直持续到药水被移除
        if (effect.getDuration() == Integer.MAX_VALUE) {
            return Integer.MAX_VALUE;
        }

        long remainingTicks = activePotion.isPermanent()
                ? Long.MAX_VALUE
                : Math.round(activePotion.getRemainingSeconds() * 20);

        // 效果覆盖了药水的全程时，随药水一起结束，这样延长药水时长也会延长效果
        if (config.getTime() > 0 && effect.getDuration() >= Math.round(config.getTime() * 20)) {
            return (int) Math.min(Integer.MAX_VALUE, remainingTicks);
        }

        long ownRemaining = effect.getDuration() - Math.round(activePotion.getElapsedSeconds() * 20);
        return (int) Math.max(0, Math.min(ownRemaining, remainingTicks));
    }

    /**
     * 移除药水带来的原版效果。
     * <p>
     * 只移除等级相同的效果，避免误删其他来源给的更高等级效果。time 为 0 的药水是瞬时生效，
     * 原版效果按自身时长继续，不在药水结束时移除。
     */
    public static void removeVanillaEffects(Player player, PotionConfig config) {
        if (config.getTime() == 0) {
            return;
        }
        for (PotionEffect effect : config.getEffects()) {
            PotionEffect current = player.getPotionEffect(effect.getType());
            if (current != null && current.getAmplifier() == effect.getAmplifier()) {
                player.removePotionEffect(effect.getType());
            }
        }
    }

    /**
     * 启动恢复任务。恢复点固定在药水开始后的第 0、1、2… 秒，
     * 中途恢复（登录、延长时长）时对齐到下一个整秒，已经过去的恢复点不会重新发放。
     *
     * @param elapsedSeconds 药水已经过去的秒数，新使用时为 0
     */
    private static void applyRegen(Player player, PotionConfig config, ActivePotion activePotion, double elapsedSeconds) {
        RegenConfig regen = config.getRegen();
        if (regen == null) {
            return;
        }

        for (Map.Entry<RegenType, RegenConfig.RegenData> entry : regen.asParsedMap().entrySet()) {
            RegenConfig.RegenData data = entry.getValue();
            double duration = data.getDuration();

            if (duration == 0) {
                if (elapsedSeconds <= 0) {
                    applyRegenTick(player, entry.getKey(), data);
                }
                continue;
            }

            int elapsedPoints = (int) Math.ceil(Math.max(0, elapsedSeconds));
            // 负数表示持续到药水结束，只有永久药水写 {time} 时才会出现
            int remainingPoints = duration < 0 ? Integer.MAX_VALUE : (int) Math.ceil(duration) - elapsedPoints;
            if (remainingPoints <= 0) {
                continue;
            }

            long delay = elapsedSeconds <= 0 ? 0L : Math.round((elapsedPoints - elapsedSeconds) * 20);
            BukkitTask task = new BukkitRunnable() {
                private int executed = 0;

                @Override
                public void run() {
                    if (!activePotion.isActive() || executed >= remainingPoints) {
                        cancel();
                        return;
                    }
                    executed++;
                    // 给死亡状态的玩家设置生命值会让客户端和服务端状态错乱
                    if (!player.isDead()) {
                        applyRegenTick(player, entry.getKey(), data);
                    }
                }
            }.runTaskTimer(AttributePotion.getInstance(), delay, 20L);
            activePotion.registerTask(task.getTaskId());
        }
    }

    private static void applyRegenTick(Player player, RegenType type, RegenConfig.RegenData data) {
        if (type == RegenType.MANA && getManaHook() == null) {
            LoggerUtil.debug("[AttributePotion] 已配置魔力恢复，但没有可用的魔力系统 Hook");
            return;
        }

        double amount = calculateRegenAmount(player, type, data);
        if (amount == 0) {
            return;
        }

        switch (type) {
            case HEALTH:
                addHealth(player, amount);
                break;
            case HUNGER:
                player.setFoodLevel((int) Math.max(0, Math.min(20, player.getFoodLevel() + amount)));
                break;
            case MANA:
                ManaHook manaHook = getManaHook();
                if (manaHook != null) {
                    manaHook.addMana(player, amount);
                }
                break;
            default:
                break;
        }
    }

    private static double calculateRegenAmount(Player player, RegenType type, RegenConfig.RegenData data) {
        double amount = data.getAmount();
        if (data.getMode() == 1) {
            return currentValue(player, type) * amount / 100.0;
        }
        if (data.getMode() == 2) {
            return maxValue(player, type) * amount / 100.0;
        }
        return amount;
    }

    private static double currentValue(Player player, RegenType type) {
        switch (type) {
            case HEALTH:
                return player.getHealth();
            case HUNGER:
                return player.getFoodLevel();
            case MANA:
                ManaHook manaHook = getManaHook();
                return manaHook == null ? 0 : manaHook.getCurrentMana(player);
            default:
                return 0;
        }
    }

    private static double maxValue(Player player, RegenType type) {
        switch (type) {
            case HEALTH:
                return getMaxHealth(player);
            case HUNGER:
                return 20;
            case MANA:
                ManaHook manaHook = getManaHook();
                return manaHook == null ? 0 : manaHook.getMaxMana(player);
            default:
                return 0;
        }
    }

    private static ManaHook getManaHook() {
        AttributePotion plugin = AttributePotion.getInstance();
        if (plugin == null) {
            return null;
        }

        ManaHook manaHook = plugin.getManaHook();
        return manaHook != null && manaHook.isAvailable() ? manaHook : null;
    }

    /**
     * 调整玩家生命值，并限制在当前服务端允许的生命范围内。
     *
     * @param amount 生命变化量，正数恢复，负数扣除
     */
    @SuppressWarnings("deprecation")
    private static void addHealth(Player player, double amount) {
        double maxHealth = getMaxHealth(player);
        if (maxHealth <= 0) {
            return;
        }
        double newHealth = Math.max(0, Math.min(maxHealth, player.getHealth() + amount));
        try {
            player.setHealth(newHealth);
        } catch (IllegalArgumentException ignored) {
            player.setHealth(Math.max(0, Math.min(player.getMaxHealth(), newHealth)));
        }
    }

    /**
     * 获取玩家最大生命值。优先读属性系统，高版本的属性名不同，所以按名称查找一次后缓存。
     */
    @SuppressWarnings("deprecation")
    private static double getMaxHealth(Player player) {
        if (MAX_HEALTH_ATTRIBUTE != null) {
            AttributeInstance instance = player.getAttribute(MAX_HEALTH_ATTRIBUTE);
            if (instance != null) {
                return instance.getValue();
            }
        }
        return player.getMaxHealth();
    }

    private static Attribute resolveMaxHealthAttribute() {
        for (String name : new String[]{"GENERIC_MAX_HEALTH", "MAX_HEALTH"}) {
            try {
                return (Attribute) Attribute.class.getMethod("valueOf", String.class).invoke(null, name);
            } catch (ReflectiveOperationException | RuntimeException | LinkageError ignored) {
                // 继续尝试下一个属性名
            }
        }
        return null;
    }

    private static void scheduleTickCommands(Player player, PotionConfig config, ActivePotion activePotion) {
        Map<Integer, List<String>> tickCommands = config.getCommands().getTickCommands();
        if (tickCommands == null || tickCommands.isEmpty()) {
            return;
        }

        for (Map.Entry<Integer, List<String>> entry : tickCommands.entrySet()) {
            int interval = entry.getKey();
            List<String> commands = entry.getValue();
            BukkitTask task = new BukkitRunnable() {
                @Override
                public void run() {
                    if (!activePotion.isActive()) {
                        cancel();
                        return;
                    }
                    CommandHandler.executeCommands(player, commands, config.getTime());
                }
            }.runTaskTimer(AttributePotion.getInstance(), interval, interval);
            activePotion.registerTask(task.getTaskId());
        }
    }

    /**
     * 为持续性药水创建并更新剩余时间 BossBar。
     */
    private static void scheduleBossBar(Player player, PotionConfig config, ActivePotion activePotion) {
        if (!config.getOptional().isEnabled(OptionKey.BOSSBAR, false)
                || activePotion.isPermanent()
                || activePotion.getTotalDurationSeconds() <= 0) {
            return;
        }

        BossBar bossBar = Bukkit.createBossBar(
                formatBossBarTitle(config, activePotion),
                parseBossBarColor(ConfigManager.getBossBarColor()),
                parseBossBarStyle(ConfigManager.getBossBarStyle())
        );
        bossBar.addPlayer(player);
        bossBar.setVisible(true);
        activePotion.registerBossBar(bossBar);
        updateBossBar(bossBar, config, activePotion);

        int interval = ConfigManager.getBossBarUpdateInterval();
        BukkitTask task = new BukkitRunnable() {
            @Override
            public void run() {
                if (!activePotion.isActive() || activePotion.isExpired()) {
                    bossBar.removeAll();
                    cancel();
                    return;
                }
                updateBossBar(bossBar, config, activePotion);
            }
        }.runTaskTimer(AttributePotion.getInstance(), interval, interval);
        activePotion.registerTask(task.getTaskId());
    }

    private static void updateBossBar(BossBar bossBar, PotionConfig config, ActivePotion activePotion) {
        double totalSeconds = activePotion.getTotalDurationSeconds();
        double remainingSeconds = activePotion.getRemainingSeconds();
        double progress = totalSeconds <= 0 ? 0 : remainingSeconds / totalSeconds;
        bossBar.setProgress(Math.max(0, Math.min(1, progress)));
        bossBar.setTitle(formatBossBarTitle(config, activePotion));
    }

    private static String formatBossBarTitle(PotionConfig config, ActivePotion activePotion) {
        double remaining = activePotion.getRemainingSeconds();
        double total = activePotion.getTotalDurationSeconds();
        double percent = total <= 0 ? 0 : remaining * 100.0 / total;
        return ConfigManager.getBossBarTitle()
                .replace("%id%", config.getKey())
                .replace("%potion%", config.getKey())
                .replace("%remaining%", formatNumber(remaining))
                .replace("%time%", formatNumber(total))
                .replace("%percent%", formatNumber(percent));
    }

    private static BarColor parseBossBarColor(String color) {
        try {
            return BarColor.valueOf(color.toUpperCase(Locale.ROOT));
        } catch (Exception ignored) {
            return BarColor.BLUE;
        }
    }

    private static BarStyle parseBossBarStyle(String style) {
        try {
            return BarStyle.valueOf(style.toUpperCase(Locale.ROOT));
        } catch (Exception ignored) {
            return BarStyle.SOLID;
        }
    }

    private static Map<String, String> potionPlaceholders(PotionConfig config) {
        Map<String, String> replacements = new HashMap<>();
        replacements.put("%id%", config.getKey());
        replacements.put("%potion%", config.getKey());
        return replacements;
    }

    /**
     * 格式化给玩家看的数字：整数不带小数点，其余保留一位小数。
     */
    private static String formatNumber(double number) {
        if (Math.abs(number - Math.rint(number)) < 0.000001) {
            return String.valueOf((long) Math.rint(number));
        }
        return String.format(Locale.ROOT, "%.1f", number);
    }

    /**
     * 根据 match-mode 和 contain 配置查找物品对应的药水。
     * <p>
     * 物品的名称、Lore、NBT 只读取一次：左右键事件非常频繁，getItemMeta 和 NBT 读取都有复制开销，
     * 不能放进遍历配置的循环里。
     *
     * @param itemStack 要检查的物品
     * @return 匹配的药水配置，不匹配时返回 null
     */
    public static PotionConfig matchPotion(ItemStack itemStack) {
        if (ItemUtil.isAir(itemStack)) {
            return null;
        }

        long start = debugStart();
        PotionConfig result = findPotion(itemStack);
        if (start != 0L) {
            debugTiming("matchPotion: mode=" + ConfigManager.getMatchMode()
                    + ", result=" + (result == null ? "null" : result.getKey()), start);
        }
        return result;
    }

    private static PotionConfig findPotion(ItemStack itemStack) {
        switch (ConfigManager.getMatchMode()) {
            case NAME:
                ItemMeta nameMeta = itemStack.hasItemMeta() ? itemStack.getItemMeta() : null;
                return nameMeta != null && nameMeta.hasDisplayName() ? matchText(nameMeta.getDisplayName()) : null;
            case LORE:
                ItemMeta loreMeta = itemStack.hasItemMeta() ? itemStack.getItemMeta() : null;
                return loreMeta != null && loreMeta.hasLore() ? matchLines(loreMeta.getLore()) : null;
            case NBT:
                if (!ConfigManager.isNbtApiAvailable()) {
                    return null;
                }
                String nbtKey = ConfigManager.getNbtKey();
                String nbtValue = NBT.get(itemStack, nbt -> {
                    return nbt.getString(nbtKey);
                });
                return nbtValue == null || nbtValue.isEmpty() ? null : matchText(nbtValue);
            default:
                return null;
        }
    }

    private static PotionConfig matchText(String text) {
        MatchIndex index = getMatchIndex();
        if (!ConfigManager.isContain()) {
            return index.exact.get(text);
        }
        for (PotionConfig config : index.longestFirst) {
            if (text.contains(config.getMatch())) {
                return config;
            }
        }
        return null;
    }

    private static PotionConfig matchLines(List<String> lines) {
        if (lines == null || lines.isEmpty()) {
            return null;
        }

        MatchIndex index = getMatchIndex();
        if (!ConfigManager.isContain()) {
            for (String line : lines) {
                PotionConfig config = line == null ? null : index.exact.get(line);
                if (config != null) {
                    return config;
                }
            }
            return null;
        }

        for (PotionConfig config : index.longestFirst) {
            for (String line : lines) {
                if (line != null && line.contains(config.getMatch())) {
                    return config;
                }
            }
        }
        return null;
    }

    private static MatchIndex getMatchIndex() {
        MatchIndex index = matchIndex;
        if (index == null) {
            index = new MatchIndex(ConfigManager.getAllPotionData().values());
            matchIndex = index;
        }
        return index;
    }

    /**
     * 判断物品是否为药水。
     */
    public static boolean isPotion(ItemStack itemStack) {
        return matchPotion(itemStack) != null;
    }

    /**
     * 仅在 debug 开启时返回计时起点。调用方拼接日志前先判断返回值是否为 0，避免关闭 debug 时白白拼字符串。
     */
    private static long debugStart() {
        return ConfigManager.isDebug() ? System.nanoTime() : 0L;
    }

    private static void debugTiming(String logic, long startNanos) {
        LoggerUtil.debug("[AttributePotion][Timing] " + logic + " took "
                + String.format(Locale.ROOT, "%.3f", (System.nanoTime() - startNanos) / 1_000_000.0) + " ms");
    }

    /**
     * 药水匹配索引，配置重载后重建。
     */
    private static final class MatchIndex {
        /**
         * contain 模式使用：按匹配值长度降序，优先命中更长的匹配值。
         */
        private final List<PotionConfig> longestFirst;
        /**
         * 完全匹配模式使用：匹配值到药水的映射。多个药水的匹配值相同时保留先加载的。
         */
        private final Map<String, PotionConfig> exact;

        private MatchIndex(Iterable<PotionConfig> configs) {
            List<PotionConfig> valid = new ArrayList<>();
            Map<String, PotionConfig> exactMap = new HashMap<>();
            for (PotionConfig config : configs) {
                String match = config.getMatch();
                if (match == null || match.isEmpty()) {
                    continue;
                }
                valid.add(config);
                PotionConfig previous = exactMap.putIfAbsent(match, config);
                if (previous != null) {
                    LoggerUtil.warning("[AttributePotion] 药水 " + previous.getKey() + " 和 " + config.getKey()
                            + " 的匹配值相同（" + match + "），后者不会被匹配到");
                }
            }
            this.longestFirst = valid.stream()
                    .sorted((a, b) -> Integer.compare(b.getMatch().length(), a.getMatch().length()))
                    .collect(Collectors.toList());
            this.exact = exactMap;
        }
    }
}
