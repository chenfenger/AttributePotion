package me.chenfeng.attributepotion.handler;

import de.tr7zw.nbtapi.NBT;
import me.chenfeng.attributepotion.AttributePotion;
import me.chenfeng.attributepotion.api.event.PotionApplyEvent;
import me.chenfeng.attributepotion.api.event.PotionUseEvent;
import me.chenfeng.attributepotion.data.ActivePotion;
import me.chenfeng.attributepotion.data.PlayerProfile;
import me.chenfeng.attributepotion.data.PotionConfig;
import me.chenfeng.attributepotion.enums.MatchMode;
import me.chenfeng.attributepotion.hook.PAPIHook;
import me.chenfeng.attributepotion.hook.attribute.AbstractAttributeHook;
import me.chenfeng.attributepotion.hook.mana.ManaHook;
import me.chenfeng.attributepotion.manager.ConfigManager;
import me.chenfeng.attributepotion.manager.PlayerManager;
import me.chenfeng.attributepotion.manager.constructor.CommandPhase;
import me.chenfeng.attributepotion.manager.constructor.OptionalConfig;
import me.chenfeng.attributepotion.manager.constructor.OptionKey;
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

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;


public class PotionHandler {

    private static List<PotionConfig> sortedPotionConfigs = null;

    /**
     * 清理药水匹配排序缓存，通常在配置重载后调用。
     */
    public static void clearCache() {
        sortedPotionConfigs = null;
    }

    /**
     * 处理玩家使用药水的入口方法。
     * <p>
     * 根据物品自动匹配对应的药水配置，如果匹配成功则应用药水效果。
     * 
     * @param player 使用药水的玩家对象
     * @param itemStack 要使用的物品堆栈
     * @return 是否成功使用药水
     */
    public static boolean usePotion(Player player, ItemStack itemStack) {
        long start = debugStart();
        PotionConfig potionConfig = matchPotion(itemStack);
        debugTiming("usePotion(item): match=" + potionName(potionConfig), start);
        return usePotion(player, itemStack, potionConfig, null);
    }

    /**
     * 按指定触发来源匹配并使用物品药水。
     */
    public static boolean usePotion(Player player, ItemStack itemStack, TriggerType triggerType) {
        long start = debugStart();
        PotionConfig potionConfig = matchPotion(itemStack);
        debugTiming("usePotion(item, trigger=" + triggerType + "): match=" + potionName(potionConfig), start);
        return usePotion(player, itemStack, potionConfig, triggerType);
    }

    /**
     * 使用已经匹配好的药水配置，避免重复执行物品匹配。
     */
    public static boolean usePotion(Player player, ItemStack itemStack, PotionConfig potionConfig, TriggerType triggerType) {
        long start = debugStart();
        if (potionConfig == null || !allowsTrigger(potionConfig, triggerType)) {
            debugTiming("usePotion(preMatched): denied/null trigger=" + triggerType, start);
            return false;
        }

        boolean used = usePotion(player, potionConfig.getKey());
        if (used) {
            applyItemCooldown(player, itemStack, potionConfig);
        }
        debugTiming("usePotion(preMatched): potion=" + potionConfig.getKey() + ", used=" + used, start);
        return used;
    }

    /**
     * 处理玩家使用药水的完整流程。
     * <p>
     * 依次检查：
     * 1. Optional 条件（潜行、覆盖等）
     * 2. 药水自身冷却
     * 3. 药水组冷却
     * 4. 使用条件（conditions）
     * 如果所有检查通过，则应用药水效果。
     *
     * @param player 使用药水的玩家对象
     * @param potionKey 药水配置键名
     * @return 是否使用成功
     */
    public static boolean usePotion(Player player, String potionKey) {
        long totalStart = debugStart();
        PotionConfig potionConfig = ConfigManager.getPotionConfig(potionKey);
        if (potionConfig == null) {
            debugTiming("usePotion(" + potionKey + "): config missing", totalStart);
            return false;
        }

        long stageStart = debugStart();
        PlayerProfile profile = PlayerManager.getOrCreateProfile(player);
        debugTiming("usePotion(" + potionKey + "): get profile", stageStart);

        stageStart = debugStart();
        if (!checkOptionalConditions(player, profile, potionConfig)) {
            debugTiming("usePotion(" + potionKey + "): optional denied", stageStart);
            debugTiming("usePotion(" + potionKey + "): total=false", totalStart);
            return false;
        }
        debugTiming("usePotion(" + potionKey + "): optional", stageStart);

        stageStart = debugStart();
        if (!checkCooldowns(player, profile, potionConfig)) {
            debugTiming("usePotion(" + potionKey + "): cooldown denied", stageStart);
            debugTiming("usePotion(" + potionKey + "): total=false", totalStart);
            return false;
        }
        debugTiming("usePotion(" + potionKey + "): cooldown", stageStart);

        stageStart = debugStart();
        if (!checkConditions(player, potionConfig)) {
            debugTiming("usePotion(" + potionKey + "): conditions denied", stageStart);
            debugTiming("usePotion(" + potionKey + "): total=false", totalStart);
            return false;
        }
        debugTiming("usePotion(" + potionKey + "): conditions", stageStart);

        stageStart = debugStart();
        if (PotionUseEvent.callEvent(player, potionConfig)) {
            debugTiming("usePotion(" + potionKey + "): event cancelled", stageStart);
            debugTiming("usePotion(" + potionKey + "): total=false", totalStart);
            return false;
        }
        debugTiming("usePotion(" + potionKey + "): event", stageStart);

        stageStart = debugStart();
        boolean applied = applyPotionEffects(player, profile, potionConfig);
        debugTiming("usePotion(" + potionKey + "): apply", stageStart);
        debugTiming("usePotion(" + potionKey + "): total=" + applied, totalStart);
        return applied;
    }

    /**
     * 直接应用药水，跳过可选条件、冷却、使用条件和使用事件。
     */
    public static boolean forceUsePotion(Player player, String potionKey) {
        long start = debugStart();
        PotionConfig potionConfig = ConfigManager.getPotionConfig(potionKey);
        if (potionConfig == null) {
            debugTiming("forceUsePotion(" + potionKey + "): config missing", start);
            return false;
        }

        PlayerProfile profile = PlayerManager.getOrCreateProfile(player);
        boolean applied = applyPotionEffects(player, profile, potionConfig);
        debugTiming("forceUsePotion(" + potionKey + "): applied=" + applied, start);
        return applied;
    }

    /**
     * 增加或减少已激活非永久药水的剩余时间。
     */
    /**
     * 预检查玩家当前是否可以使用指定药水。
     * <p>
     * 该方法只做条件判断，不触发使用事件，也不应用药水效果。按键蓄力开始前可用它决定是否通知前端显示进度。
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

        PlayerProfile profile = PlayerManager.getOrCreateProfile(player);
        return checkOptionalConditions(player, profile, potionConfig)
                && checkCooldowns(player, profile, potionConfig)
                && checkConditions(player, potionConfig);
    }

    public static boolean addPotionTime(Player player, String potionKey, double seconds) {
        long start = debugStart();
        if (seconds == 0) {
            debugTiming("addPotionTime(" + potionKey + "): zero seconds", start);
            return false;
        }

        PlayerProfile profile = PlayerManager.getProfile(player);
        if (profile == null) {
            debugTiming("addPotionTime(" + potionKey + "): profile missing", start);
            return false;
        }

        ActivePotion activePotion = profile.getActivePotions().get(potionKey);
        PotionConfig config = ConfigManager.getPotionConfig(potionKey);
        if (activePotion == null || config == null || activePotion.isPermanent()) {
            debugTiming("addPotionTime(" + potionKey + "): active/config missing or permanent", start);
            return false;
        }

        long stageStart = debugStart();
        activePotion.cancelAllTickTasks();
        activePotion.addTime(seconds);
        debugTiming("addPotionTime(" + potionKey + "): update active time", stageStart);
        if (activePotion.isExpired()) {
            PlayerManager.removeActivePotion(player, profile, potionKey);
            debugTiming("addPotionTime(" + potionKey + "): removed expired potion", start);
            return true;
        }

        stageStart = debugStart();
        applyVanillaEffects(player, config, activePotion.getRemainingSeconds());
        debugTiming("addPotionTime(" + potionKey + "): refresh vanilla effects", stageStart);
        stageStart = debugStart();
        applyRegen(player, config, activePotion, activePotion.getElapsedSeconds());
        debugTiming("addPotionTime(" + potionKey + "): reschedule regen", stageStart);
        stageStart = debugStart();
        scheduleTickCommands(player, config, activePotion);
        debugTiming("addPotionTime(" + potionKey + "): reschedule tick commands", stageStart);
        stageStart = debugStart();
        scheduleBossBar(player, config, activePotion);
        debugTiming("addPotionTime(" + potionKey + "): reschedule bossbar", stageStart);
        debugTiming("addPotionTime(" + potionKey + "): total", start);
        return true;
    }

    private static boolean allowsTrigger(PotionConfig config, TriggerType triggerType) {
        if (triggerType == null) {
            return true;
        }
        return config.getTriggers() == null
                || config.getTriggers().isEmpty()
                || config.getTriggers().contains(TriggerType.ALL)
                || config.getTriggers().contains(triggerType);
    }

    /**
     * 当药水启用 COOL 选项时，应用 Bukkit 原版物品冷却显示。
     */
    private static void applyItemCooldown(Player player, ItemStack itemStack, PotionConfig config) {
        if (!config.getOptional().isEnabled(OptionKey.COOL, false) || config.getCooldown() <= 0) {
            return;
        }

        player.setCooldown(itemStack.getType(), (int) Math.round(config.getCooldown() * 20));
    }

    /**
     * 检查 Optional 配置中的条件。
     * <p>
     * 包括：
     * - SHIFT: 是否需要潜行
     * - COVER: 是否允许覆盖已有药水效果
     * 
     * @param player 玩家对象
     * @param profile 玩家档案
     * @param config 药水配置
     * @return 是否满足所有 Optional 条件
     */
    private static boolean checkOptionalConditions(Player player, PlayerProfile profile, PotionConfig config) {
        OptionalConfig optional = config.getOptional();

        if (optional.isEnabled(OptionKey.SHIFT, false) && !player.isSneaking()) {
            ConfigManager.sendMessage(player, "shift", null);
            return false;
        }

        if (!optional.isEnabled(OptionKey.COVER, true)) {
            if (profile.getActivePotions().containsKey(config.getKey())) {
                Map<String, String> replacements = new HashMap<>();
                replacements.put("%id%", config.getKey());
                ConfigManager.sendMessage(player, "cover-deny", replacements);
                return false;
            }
        }

        return true;
    }

    /**
     * 检查药水和药水组的冷却时间。
     * <p>
     * 根据配置检查：
     * - 药水自身冷却
     * - 药水组冷却
     * 
     * @param player 玩家对象
     * @param profile 玩家档案
     * @param config 药水配置
     * @return 是否不在冷却中
     */
    private static boolean checkCooldowns(Player player, PlayerProfile profile, PotionConfig config) {

        if (profile.isPotionOnCooldown(config.getKey())) {
            Map<String, String> replacements = new HashMap<>();
            replacements.put("%potion%", config.getKey());
            replacements.put("%cooldown%", formatSeconds(profile.getPotionCooldownRemaining(config.getKey())));
            ConfigManager.sendMessage(player, "on-potion-cooldown", replacements);
            return false;
        }

        String groupName = config.getGroup();
        if (groupName != null && !groupName.isEmpty()) {
            if (profile.isGroupOnCooldown(groupName)) {
                Map<String, String> replacements = new HashMap<>();
                replacements.put("%group%", groupName);
                replacements.put("%cooldown%", formatSeconds(profile.getGroupCooldownRemaining(groupName)));
                ConfigManager.sendMessage(player, "on-group-cooldown", replacements);
                return false;
            }
        }

        return true;
    }

    /**
     * 检查药水的使用条件。
     * <p>
     * 条件格式：表达式&lt;-&gt失败消息
     * 例如："%player_level% >= 10&lt;-&gt&c你的等级不足10级!"
     * 
     * @param player 玩家对象
     * @param config 药水配置
     * @return 是否满足所有条件
     */
    private static boolean checkConditions(Player player, PotionConfig config) {
        List<String> conditions = config.getConditions();
        if (conditions == null || conditions.isEmpty()) {
            return true;
        }

        for (String condition : conditions) {
            if (condition == null || condition.isEmpty()) {
                continue;
            }

            String[] parts = condition.split(java.util.regex.Pattern.quote(ConfigManager.getSplit()), 2);
            if (parts.length != 2) {
                continue;
            }

            String expression = parts[0].trim();
            String failMessage = parts[1].trim();

            String replacedExpression = PAPIHook.replacePlaceholders(player, expression);
            
            try {
                boolean result = ExpressionUtil.check(replacedExpression);
                if (!result) {
                    Map<String, String> replacements = new HashMap<>();
                    replacements.put("%id%", config.getKey());
                    player.sendMessage(failMessage.replace("%id%", config.getKey()));
                    ConfigManager.sendMessage(player, "use-deny", replacements);
                    return false;
                }
            } catch (Exception e) {
                LoggerUtil.warning("[AttributePotion] 条件表达式解析失败: " + expression);
                e.printStackTrace();
                return false;
            }
        }

        return true;
    }

    /**
     * 应用药水效果。
     * <p>
     * 执行以下操作：
     * 1. 创建 ActivePotion 实例
     * 2. 应用属性加成
     * 3. 应用原版药水效果
     * 4. 设置冷却时间
     * 5. 执行 SUCCESS 命令
     * 
     * @param player 玩家对象
     * @param profile 玩家档案
     * @param config 药水配置
     * @return 是否成功应用
     */
    private static boolean applyPotionEffects(Player player, PlayerProfile profile, PotionConfig config) {
        return applyPotionEffects(player, profile, config, true);
    }

    private static boolean applyPotionEffects(Player player, PlayerProfile profile, PotionConfig config, boolean allowRange) {
        long totalStart = debugStart();
        long stageStart = debugStart();
        PlayerManager.removeActivePotion(player, profile, config.getKey());

        long durationMillis = config.getTime() < 0 ? -1 : (long) (config.getTime() * 1000);
        ActivePotion activePotion = new ActivePotion(config.getKey(), player, durationMillis);

        profile.addActivePotion(config.getKey(), activePotion);
        profile.setPotionCooldown(config.getKey(), config.getCooldown());

        String groupName = config.getGroup();
        if (groupName != null && !groupName.isEmpty()) {
            profile.setGroupCooldown(groupName, config.getCooldown());
        }
        debugTiming("applyPotionEffects(" + config.getKey() + "): state/cooldown", stageStart);

        stageStart = debugStart();
        applyAttributes(player, config, activePotion);
        debugTiming("applyPotionEffects(" + config.getKey() + "): attributes", stageStart);
        stageStart = debugStart();
        applyVanillaEffects(player, config);
        debugTiming("applyPotionEffects(" + config.getKey() + "): vanilla effects", stageStart);
        stageStart = debugStart();
        applyRegen(player, config, activePotion);
        debugTiming("applyPotionEffects(" + config.getKey() + "): regen", stageStart);
        stageStart = debugStart();
        scheduleTickCommands(player, config, activePotion);
        debugTiming("applyPotionEffects(" + config.getKey() + "): tick commands", stageStart);
        stageStart = debugStart();
        scheduleBossBar(player, config, activePotion);
        debugTiming("applyPotionEffects(" + config.getKey() + "): bossbar", stageStart);

        Map<String, String> replacements = new HashMap<>();
        replacements.put("%id%", config.getKey());
        replacements.put("%potion%", config.getKey());
        replacements.put("%time%", String.valueOf(config.getTime()));
        stageStart = debugStart();
        ConfigManager.sendMessage(player, "use-potion", replacements);
        debugTiming("applyPotionEffects(" + config.getKey() + "): message", stageStart);

        stageStart = debugStart();
        List<String> successCommands = config.getCommands().get(CommandPhase.SUCCESS);
        CommandHandler.executeCommands(player, successCommands, config.getTime());
        debugTiming("applyPotionEffects(" + config.getKey() + "): success commands", stageStart);
        stageStart = debugStart();
        PotionApplyEvent.callEvent(player, config, activePotion);
        debugTiming("applyPotionEffects(" + config.getKey() + "): apply event", stageStart);
        if (allowRange) {
            stageStart = debugStart();
            applyRangeEffects(player, config);
            debugTiming("applyPotionEffects(" + config.getKey() + "): range", stageStart);
        }

        LoggerUtil.debug("[AttributePotion] 玩家 " + player.getName() + " 成功使用药水 " + config.getKey());

        debugTiming("applyPotionEffects(" + config.getKey() + "): total", totalStart);
        return true;
    }

    /**
     * 玩家数据加载完成后恢复持久化保存的激活药水。
     */
    public static void restoreActivePotion(Player player, ActivePotion activePotion) {
        long start = debugStart();
        PotionConfig config = ConfigManager.getPotionConfig(activePotion.getPotionKey());
        if (config == null || activePotion.isExpired()) {
            debugTiming("restoreActivePotion(" + activePotion.getPotionKey() + "): skipped", start);
            return;
        }

        applyStoredAttributes(player, activePotion);
        applyVanillaEffects(player, config, activePotion.getRemainingSeconds());
        applyRegen(player, config, activePotion, activePotion.getElapsedSeconds());
        scheduleTickCommands(player, config, activePotion);
        scheduleBossBar(player, config, activePotion);
        debugTiming("restoreActivePotion(" + activePotion.getPotionKey() + "): total", start);
    }

    /**
     * 重新应用激活药水快照中保存的属性。
     */
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
            if (target.equals(source) || !target.getWorld().equals(source.getWorld())) {
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

            Map<String, String> otherReplacements = new HashMap<>();
            otherReplacements.put("%player%", source.getName());
            otherReplacements.put("%potion%", config.getKey());
            otherReplacements.put("%id%", config.getKey());
            ConfigManager.sendMessage(target, "other-potion", otherReplacements);
        }

        if (!affectedNames.isEmpty()) {
            Map<String, String> replacements = new HashMap<>();
            replacements.put("%range%", formatNumber(config.getDistance()));
            replacements.put("%playerList%", String.join(", ", affectedNames));
            replacements.put("%potion%", config.getKey());
            replacements.put("%id%", config.getKey());
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
            return new ArrayList<>();
        }

        List<String> attributes = new ArrayList<>();
        for (Map.Entry<String, String> entry : configured.entrySet()) {
            if (entry.getKey() == null || entry.getKey().isEmpty()) {
                continue;
            }

            String value = entry.getValue() == null ? "" : PAPIHook.replacePlaceholders(player, entry.getValue()).trim();
            if (value.isEmpty()) {
                continue;
            }

            attributes.add(entry.getKey() + ": " + formatAttributeValue(value));
        }
        return attributes;
    }

    private static String formatAttributeValue(String value) {
        try {
            double result = ExpressionUtil.eval(value);
            return formatNumber(result);
        } catch (IllegalArgumentException ignored) {
            return value;
        }
    }

    private static void applyVanillaEffects(Player player, PotionConfig config) {
        applyVanillaEffects(player, config, -1);
    }

    private static void applyVanillaEffects(Player player, PotionConfig config, double maxRemainingSeconds) {
        List<PotionEffect> effects = config.getEffects();
        if (effects == null || effects.isEmpty()) {
            return;
        }

        for (PotionEffect effect : effects) {
            PotionEffect applied = effect;
            if (maxRemainingSeconds >= 0) {
                int ticks = (int) Math.max(0, Math.min(effect.getDuration(), Math.round(maxRemainingSeconds * 20)));
                if (ticks <= 0) {
                    continue;
                }
                applied = new PotionEffect(effect.getType(), ticks, effect.getAmplifier(), effect.isAmbient(), effect.hasParticles());
            }
            player.addPotionEffect(applied, true);
        }
    }

    private static void applyRegen(Player player, PotionConfig config, ActivePotion activePotion) {
        applyRegen(player, config, activePotion, 0);
    }

    private static void applyRegen(Player player, PotionConfig config, ActivePotion activePotion, double elapsedSeconds) {
        RegenConfig regen = config.getRegen();
        if (regen == null || regen.asParsedMap().isEmpty()) {
            return;
        }

        for (Map.Entry<RegenType, RegenConfig.RegenData> entry : regen.asParsedMap().entrySet()) {
            RegenConfig.RegenData data = entry.getValue();
            if (data == null) {
                continue;
            }

            double remainingSeconds = data.getDuration() - elapsedSeconds;
            if (data.getDuration() <= 0 && elapsedSeconds <= 0) {
                applyRegenTick(player, entry.getKey(), data);
                continue;
            }
            if (remainingSeconds <= 0) {
                continue;
            }

            BukkitTask task = new BukkitRunnable() {
                private int elapsedSeconds = 0;
                private final int maxSeconds = (int) Math.ceil(remainingSeconds);

                @Override
                public void run() {
                    if (!activePotion.isActive() || elapsedSeconds >= maxSeconds) {
                        cancel();
                        return;
                    }

                    applyRegenTick(player, entry.getKey(), data);
                    elapsedSeconds++;
                }
            }.runTaskTimer(AttributePotion.getInstance(), 0L, 20L);
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
                return getCurrentHealth(player);
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

    /**
     * 获取当前可用的魔力 Hook。
     *
     * @return 魔力 Hook，不可用时返回 null
     */
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
     * @param player 玩家
     * @param amount 生命变化量，正数恢复，负数扣除
     */
    private static void addHealth(Player player, double amount) {
        double maxHealth = getMaxHealth(player);
        if (maxHealth <= 0) {
            return;
        }

        double currentHealth = getCurrentHealth(player);
        double newHealth = Math.max(0, Math.min(maxHealth, currentHealth + amount));
        try {
            player.setHealth(newHealth);
        } catch (IllegalArgumentException ignored) {
            player.setHealth(Math.max(0, Math.min(player.getMaxHealth(), newHealth)));
        }
    }

    /**
     * 获取玩家当前生命值。
     *
     * @param player 玩家
     * @return 当前生命值
     */
    private static double getCurrentHealth(Player player) {
        try {
            return player.getHealth();
        } catch (IllegalArgumentException ignored) {
            return 0;
        }
    }

    /**
     * 获取玩家最大生命值。
     * <p>
     * 高版本 Bukkit 更推荐通过 Attribute 读取生命上限。这里优先尝试属性系统，并兼容不同版本的属性名；
     * 如果属性不可用，再回退到旧版 Player#getMaxHealth。
     *
     * @param player 玩家
     * @return 最大生命值
     */
    @SuppressWarnings("deprecation")
    private static double getMaxHealth(Player player) {
        try {
            AttributeInstance attribute = getMaxHealthAttribute(player);
            if (attribute != null) {
                return attribute.getValue();
            }
        } catch (Throwable ignored) {
        }

        return player.getMaxHealth();
    }

    /**
     * 获取最大生命属性实例。
     *
     * @param player 玩家
     * @return 属性实例，不存在时返回 null
     */
    private static AttributeInstance getMaxHealthAttribute(Player player) {
        Attribute attribute = findAttribute("GENERIC_MAX_HEALTH");
        if (attribute == null) {
            attribute = findAttribute("MAX_HEALTH");
        }
        return attribute == null ? null : player.getAttribute(attribute);
    }

    /**
     * 按名称查找 Bukkit 属性。
     *
     * @param name 属性枚举名
     * @return 属性枚举，不存在时返回 null
     */
    private static Attribute findAttribute(String name) {
        try {
            Class<?> attributeClass = Class.forName("org.bukkit.attribute.Attribute");
            Object attribute = attributeClass.getMethod("valueOf", String.class).invoke(null, name);
            return (Attribute) attribute;
        } catch (Throwable ignored) {
            return null;
        }
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

        BukkitTask task = new BukkitRunnable() {
            @Override
            public void run() {
                if (!activePotion.isActive() || activePotion.isExpired() || !player.isOnline()) {
                    bossBar.removeAll();
                    cancel();
                    return;
                }
                updateBossBar(bossBar, config, activePotion);
            }
        }.runTaskTimer(AttributePotion.getInstance(), ConfigManager.getBossBarUpdateInterval(), ConfigManager.getBossBarUpdateInterval());
        activePotion.registerTask(task.getTaskId());
    }

    /**
     * 更新 BossBar 标题和进度。
     */
    private static void updateBossBar(BossBar bossBar, PotionConfig config, ActivePotion activePotion) {
        double totalSeconds = activePotion.getTotalDurationSeconds();
        double remainingSeconds = activePotion.getRemainingSeconds();
        double progress = totalSeconds <= 0 ? 0 : remainingSeconds / totalSeconds;
        bossBar.setProgress(Math.max(0, Math.min(1, progress)));
        bossBar.setTitle(formatBossBarTitle(config, activePotion));
    }

    /**
     * 格式化 BossBar 标题占位符。
     */
    private static String formatBossBarTitle(PotionConfig config, ActivePotion activePotion) {
        double remaining = activePotion.getRemainingSeconds();
        double total = activePotion.getTotalDurationSeconds();
        double percent = total <= 0 ? 0 : remaining * 100.0 / total;
        return ConfigManager.getBossBarTitle()
                .replace("%id%", config.getKey())
                .replace("%potion%", config.getKey())
                .replace("%remaining%", formatSeconds(remaining))
                .replace("%time%", formatSeconds(total))
                .replace("%percent%", formatSeconds(percent));
    }

    /**
     * 解析 BossBar 颜色。
     */
    private static BarColor parseBossBarColor(String color) {
        try {
            return BarColor.valueOf(color.toUpperCase(java.util.Locale.ROOT));
        } catch (Exception ignored) {
            return BarColor.BLUE;
        }
    }

    /**
     * 解析 BossBar 样式。
     */
    private static BarStyle parseBossBarStyle(String style) {
        try {
            return BarStyle.valueOf(style.toUpperCase(java.util.Locale.ROOT));
        } catch (Exception ignored) {
            return BarStyle.SOLID;
        }
    }

    private static String formatSeconds(double seconds) {
        return formatNumber(seconds);
    }

    private static String formatNumber(double number) {
        if (Math.abs(number - Math.rint(number)) < 0.000001) {
            return String.valueOf((long) Math.rint(number));
        }
        return String.format(java.util.Locale.ROOT, "%.1f", number);
    }



    /**
     * 检查物品是否为药水，并返回匹配的药水配置。
     * <p>
     * 根据 ConfigManager 中配置的匹配模式（NAME/LORE/NBT）和包含模式（contain），
     * 遍历所有药水配置，使用精确匹配或贪婪包含匹配找到对应的药水。
     * 
     * @param itemStack 要检查的物品
     * @return 匹配的药水配置，如果不匹配则返回 null
     */
    public static PotionConfig matchPotion(ItemStack itemStack) {
        long start = debugStart();
        if (ItemUtil.isAir(itemStack)) {
            debugTiming("matchPotion: air/null item", start);
            return null;
        }

        MatchMode matchMode = ConfigManager.getMatchMode();
        boolean contain = ConfigManager.isContain();
        Map<String, PotionConfig> allPotions = ConfigManager.getAllPotionData();

        PotionConfig result;
        if (contain) {
            result = matchWithContain(itemStack, matchMode, allPotions);
        } else {
            result = matchExact(itemStack, matchMode, allPotions);
        }
        debugTiming("matchPotion: mode=" + matchMode
                + ", contain=" + contain
                + ", configs=" + allPotions.size()
                + ", result=" + potionName(result), start);
        return result;
    }

    /**
     * 完全匹配模式：查找完全相等的匹配值。
     * 
     * @param itemStack 物品
     * @param matchMode 匹配模式
     * @param allPotions 所有药水配置
     * @return 匹配的药水配置，如果不匹配则返回 null
     */
    private static PotionConfig matchExact(ItemStack itemStack, MatchMode matchMode, Map<String, PotionConfig> allPotions) {
        long start = debugStart();
        int checked = 0;
        for (PotionConfig potionConfig : allPotions.values()) {
            String matchValue = potionConfig.getMatch();
            if (matchValue == null || matchValue.isEmpty()) {
                continue;
            }
            checked++;

            boolean matched = false;

            switch (matchMode) {
                case NAME:
                    matched = matchByName(itemStack, matchValue, false);
                    break;
                case LORE:
                    matched = matchByLore(itemStack, matchValue, false);
                    break;
                case NBT:
                    matched = matchByNBT(itemStack, matchValue, false);
                    break;
                default:
                    break;
            }

            if (matched) {
                debugTiming("matchExact: checked=" + checked + ", result=" + potionConfig.getKey(), start);
                return potionConfig;
            }
        }

        debugTiming("matchExact: checked=" + checked + ", result=null", start);
        return null;
    }

    /**
     * 包含匹配模式：使用贪婪匹配策略，优先匹配最长的字符串。
     * <p>
     * 性能优化：按匹配值长度降序排序，找到第一个匹配立即返回。
     * 
     * @param itemStack 物品
     * @param matchMode 匹配模式
     * @param allPotions 所有药水配置
     * @return 匹配的药水配置，如果不匹配则返回 null
     */
    private static PotionConfig matchWithContain(ItemStack itemStack, MatchMode matchMode, Map<String, PotionConfig> allPotions) {
        long start = debugStart();
        if (sortedPotionConfigs == null) {
            sortedPotionConfigs = allPotions.values().stream()
                    .filter(config -> config.getMatch() != null && !config.getMatch().isEmpty())
                    .sorted((a, b) -> Integer.compare(b.getMatch().length(), a.getMatch().length()))
                    .collect(Collectors.toList());
            debugTiming("matchWithContain: rebuild sorted cache size=" + sortedPotionConfigs.size(), start);
            start = debugStart();
        }

        int checked = 0;
        for (PotionConfig potionConfig : sortedPotionConfigs) {
            String matchValue = potionConfig.getMatch();
            if (matchValue == null || matchValue.isEmpty()) {
                continue;
            }
            checked++;

            boolean matched = false;

            switch (matchMode) {
                case NAME:
                    matched = matchByName(itemStack, matchValue, true);
                    break;
                case LORE:
                    matched = matchByLore(itemStack, matchValue, true);
                    break;
                case NBT:
                    matched = matchByNBT(itemStack, matchValue, true);
                    break;
                default:
                    break;
            }

            if (matched) {
                debugTiming("matchWithContain: checked=" + checked + ", result=" + potionConfig.getKey(), start);
                return potionConfig;
            }
        }

        debugTiming("matchWithContain: checked=" + checked + ", result=null", start);
        return null;
    }

    /**
     * 通过物品名称匹配药水。
     * <p>
     * - 完全匹配：物品显示名称与配置中的 match 字符串完全相等
     * - 包含匹配：物品显示名称包含配置中的 match 字符串（贪婪匹配，优先匹配更长的）
     * 
     * @param itemStack 物品
     * @param matchValue 配置中的匹配字符串
     * @param contain 是否使用包含匹配
     * @return 是否匹配
     */
    private static boolean matchByName(ItemStack itemStack, String matchValue, boolean contain) {
        ItemMeta meta = itemStack.getItemMeta();
        if (meta == null || !meta.hasDisplayName()) {
            return false;
        }

        String displayName = meta.getDisplayName();
        
        if (contain) {
            return displayName.contains(matchValue);
        } else {
            return displayName.equals(matchValue);
        }
    }

    /**
     * 通过物品 Lore 匹配药水。
     * <p>
     * - 完全匹配：物品的某一行 Lore 与配置中的 match 字符串完全相等
     * - 包含匹配：物品的某一行 Lore 包含配置中的 match 字符串（贪婪匹配，优先匹配更长的）
     * 
     * @param itemStack 物品
     * @param matchValue 配置中的匹配字符串
     * @param contain 是否使用包含匹配
     * @return 是否匹配
     */
    private static boolean matchByLore(ItemStack itemStack, String matchValue, boolean contain) {
        ItemMeta meta = itemStack.getItemMeta();
        if (meta == null || !meta.hasLore()) {
            return false;
        }

        List<String> lore = meta.getLore();
        if (lore == null || lore.isEmpty()) {
            return false;
        }

        for (String loreLine : lore) {
            if (loreLine != null) {
                if (contain) {
                    if (loreLine.contains(matchValue)) {
                        return true;
                    }
                } else {
                    if (loreLine.equals(matchValue)) {
                        return true;
                    }
                }
            }
        }

        return false;
    }

    /**
     * 通过 NBT 标签匹配药水。
     * <p>
     * - 完全匹配：NBT 值与配置中的 match 字符串完全相等
     * - 包含匹配：NBT 值包含配置中的 match 字符串
     * 
     * @param itemStack 物品
     * @param matchValue 配置中的匹配字符串
     * @param contain 是否使用包含匹配
     * @return 是否匹配
     */
    private static boolean matchByNBT(ItemStack itemStack, String matchValue, boolean contain) {
        if (!ConfigManager.isNbtApiAvailable()) {
            return false;
        }

        String nbtKey = ConfigManager.getNbtKey();
        
        return NBT.get(itemStack, readableNBT -> {
            if (readableNBT == null) {
                return false;
            }

            String nbtValue = readableNBT.getString(nbtKey);
            if (nbtValue == null) {
                return false;
            }

            if (contain) {
                return nbtValue.contains(matchValue);
            } else {
                return nbtValue.equals(matchValue);
            }
        });
    }

    /**
     * 检查物品是否为药水（简化版本，只返回布尔值）。
     * 
     * @param itemStack 要检查的物品
     * @return 如果是药水返回 true，否则返回 false
     */
    public static boolean isPotion(ItemStack itemStack) {
        return matchPotion(itemStack) != null;
    }

    /**
     * 仅在 debug 开启时返回计时起点。
     */
    private static long debugStart() {
        return ConfigManager.isDebug() ? System.nanoTime() : 0L;
    }

    /**
     * 在 debug 开启时输出耗时日志。
     */
    private static void debugTiming(String logic, long startNanos) {
        if (startNanos == 0L || !ConfigManager.isDebug()) {
            return;
        }
        LoggerUtil.debug("[AttributePotion][Timing] " + logic + " took "
                + formatMillis(System.nanoTime() - startNanos) + " ms");
    }

    /**
     * 将纳秒格式化为保留三位小数的毫秒字符串。
     */
    private static String formatMillis(long nanos) {
        return String.format(java.util.Locale.ROOT, "%.3f", nanos / 1_000_000.0);
    }

    /**
     * 将可能为空的药水配置转换为可读的调试名称。
     */
    private static String potionName(PotionConfig config) {
        return config == null ? "null" : config.getKey();
    }
}
