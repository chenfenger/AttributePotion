package me.chenfeng.attributepotion.data;

import lombok.Getter;
import lombok.NonNull;
import org.bukkit.Bukkit;
import org.bukkit.boss.BossBar;
import org.bukkit.entity.Player;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 激活的药水实例类，表示玩家当前正在使用的药水效果。
 * <p>
 * 每个 ActivePotion 实例对应一个玩家使用的一次药水，
 * 记录了该次使用的具体参数、应用的状态以及生命周期信息。
 * 与 PotionConfig（配置模板）不同，ActivePotion 是运行时数据。
 */
@Getter
public class ActivePotion {
    private final String potionKey;
    private final Player player;
    private final long useTime;
    private long endTime;
    private final List<String> appliedAttributes;
    private final List<BossBar> bossBars;
    private final Map<Integer, Integer> tickTaskIds;
    private volatile boolean isActive;

    /**
     * 构造激活的药水实例。
     * 
     * @param potionKey 药水配置键名
     * @param player 使用该药水的玩家
     * @param durationMillis 药水持续时间（毫秒），-1 表示永久
     */
    public ActivePotion(@NonNull String potionKey, @NonNull Player player, long durationMillis) {
        this(potionKey, player, System.currentTimeMillis(), durationMillis);
    }

    private ActivePotion(@NonNull String potionKey, @NonNull Player player, long useTime, long durationMillis) {
        this(potionKey,
                player,
                useTime,
                durationMillis < 0 ? Long.MAX_VALUE : useTime + durationMillis,
                null);
    }

    public ActivePotion(@NonNull String potionKey, @NonNull Player player, long useTime, long endTime, List<String> appliedAttributes) {
        this.potionKey = potionKey;
        this.player = player;
        this.useTime = useTime;
        this.endTime = endTime < 0 ? Long.MAX_VALUE : endTime;
        this.appliedAttributes = appliedAttributes == null ? new ArrayList<>() : new ArrayList<>(appliedAttributes);
        this.bossBars = new CopyOnWriteArrayList<>();
        this.tickTaskIds = new ConcurrentHashMap<>();
        this.isActive = true;
    }

    /**
     * 检查药水是否已过期。
     * 
     * @return 如果当前时间超过结束时间则返回 true
     */
    public boolean isExpired() {
        return System.currentTimeMillis() >= endTime;
    }

    /**
     * 检查药水是否为永久效果。
     * 
     * @return 如果结束时间为 Long.MAX_VALUE 则返回 true
     */
    public boolean isPermanent() {
        return endTime == Long.MAX_VALUE;
    }

    /**
     * 获取剩余持续时间（秒）。
     * 
     * @return 剩余秒数，如果是永久效果则返回 -1
     */
    public double getRemainingSeconds() {
        if (isPermanent()) {
            return -1;
        }
        long remaining = endTime - System.currentTimeMillis();
        return Math.max(0, remaining / 1000.0);
    }

    /**
     * 添加已应用的属性标识。
     * <p>
     * 用于在药水结束时追踪并移除这些属性。
     * 
     * @param attribute 属性标识字符串
     */
    public void addAppliedAttribute(@NonNull String attribute) {
        if (isActive) {
            appliedAttributes.add(attribute);
        }
    }

    /**
     * 批量添加已应用的属性标识。
     * 
     * @param attributes 属性标识列表
     */
    public void addAppliedAttributes(@NonNull List<String> attributes) {
        if (isActive) {
            appliedAttributes.addAll(attributes);
        }
    }

    /**
     * 注册 tick 周期任务 ID。
     * <p>
     * 用于在药水结束时取消所有相关的定时任务。
     * 
     * @param tickInterval tick 间隔（游戏刻）
     * @param taskId Bukkit 调度任务 ID
     */
    public void registerTickTask(int tickInterval, int taskId) {
        if (isActive) {
            tickTaskIds.put(tickInterval, taskId);
        }
    }

    public void registerTask(int taskId) {
        if (isActive) {
            tickTaskIds.put(taskId, taskId);
        }
    }

    public void registerBossBar(@NonNull BossBar bossBar) {
        if (isActive) {
            bossBars.add(bossBar);
        } else {
            bossBar.removeAll();
        }
    }

    /**
     * 取消所有注册的 tick 任务。
     * <p>
     * 应在药水结束时调用，防止内存泄漏。
     */
    public void cancelAllTickTasks() {
        for (Integer taskId : tickTaskIds.values()) {
            Bukkit.getScheduler().cancelTask(taskId);
        }
        tickTaskIds.clear();
        for (BossBar bossBar : bossBars) {
            bossBar.removeAll();
        }
        bossBars.clear();
    }

    /**
     * 停用此药水实例。
     * <p>
     * 标记为不活跃状态，防止后续操作。
     */
    public void deactivate() {
        this.isActive = false;
        cancelAllTickTasks();
    }

    public void addTime(double seconds) {
        if (isPermanent() || seconds == 0) {
            return;
        }

        long baseTime = Math.max(System.currentTimeMillis(), endTime);
        this.endTime = baseTime + (long) (seconds * 1000);
    }

    /**
     * 获取药水已经持续的时间（秒）。
     * 
     * @return 已持续的秒数
     */
    public double getElapsedSeconds() {
        long elapsed = System.currentTimeMillis() - useTime;
        return elapsed / 1000.0;
    }

    /**
     * 获取药水的总持续时间（秒）。
     * 
     * @return 总持续秒数，如果是永久效果则返回 -1
     */
    public double getTotalDurationSeconds() {
        if (isPermanent()) {
            return -1;
        }
        return (endTime - useTime) / 1000.0;
    }
}
