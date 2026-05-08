package me.chenfeng.attributepotion.listener.hook;

import org.bukkit.entity.Player;
import org.bukkit.scheduler.BukkitTask;

import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 记录按键蓄力时间。
 * <p>
 * 按住时会累加蓄力值；松开后不会立刻清零，而是按真实时间逐渐衰减。
 * 这样玩家短暂松开按键后再次按下，可以接着剩余蓄力继续累计。
 */
final class KeyPressTracker {
    private static final Map<String, PressState> PRESS_STATE_MAP = new ConcurrentHashMap<>();
    private static final Map<String, BukkitTask> TASK_MAP = new ConcurrentHashMap<>();
    private static final Map<UUID, String> ACTIVE_KEY_MAP = new ConcurrentHashMap<>();

    private KeyPressTracker() {
    }

    /**
     * 记录玩家按下指定按键。
     *
     * @param player 玩家
     * @param key 按键名
     */
    static boolean record(Player player, String key) {
        if (player == null || key == null) {
            return false;
        }

        long now = System.currentTimeMillis();
        UUID uuid = player.getUniqueId();
        String normalized = KeySlotPotionHandler.normalizeKey(key).toUpperCase(Locale.ROOT);
        String previousKey = ACTIVE_KEY_MAP.put(uuid, normalized);
        if (previousKey != null && !previousKey.equals(normalized)) {
            clear(player, previousKey);
        }

        PressState state = PRESS_STATE_MAP.computeIfAbsent(mapKey(uuid, normalized), ignored -> new PressState(now));
        synchronized (state) {
            state.decay(now);
            if (state.isPressing()) {
                return false;
            }
            state.pressStartMillis = now;
            return true;
        }
    }

    /**
     * 清理玩家指定按键的蓄力记录。
     *
     * @param player 玩家
     * @param key 按键名
     */
    static void clear(Player player, String key) {
        if (player == null || key == null) {
            return;
        }
        String mapKey = mapKey(player.getUniqueId(), key);
        PRESS_STATE_MAP.remove(mapKey);
        cancelTask(mapKey);

        String normalized = KeySlotPotionHandler.normalizeKey(key).toUpperCase(Locale.ROOT);
        ACTIVE_KEY_MAP.remove(player.getUniqueId(), normalized);
    }

    /**
     * 清理玩家当前正在处理的按键。
     *
     * @param player 玩家
     */
    static void clearActive(Player player) {
        if (player == null) {
            return;
        }

        String activeKey = ACTIVE_KEY_MAP.remove(player.getUniqueId());
        if (activeKey != null) {
            String mapKey = mapKey(player.getUniqueId(), activeKey);
            PRESS_STATE_MAP.remove(mapKey);
            cancelTask(mapKey);
        }
    }

    /**
     * 记录松开按键，并返回当前蓄力秒数。
     * <p>
     * 该方法不会清零蓄力，调用方应在成功触发药水后调用 {@link #clear(Player, String)}。
     *
     * @param player 玩家
     * @param key 按键名
     * @return 当前可用蓄力秒数
     */
    static double releaseSeconds(Player player, String key) {
        if (player == null || key == null) {
            return 0;
        }

        PressState state = PRESS_STATE_MAP.get(mapKey(player.getUniqueId(), key));
        if (state == null) {
            return 0;
        }

        long now = System.currentTimeMillis();
        synchronized (state) {
            if (state.isPressing()) {
                state.chargeSeconds += Math.max(0, (now - state.pressStartMillis) / 1000.0);
                state.pressStartMillis = -1;
                state.lastReleaseMillis = now;
            } else {
                state.decay(now);
            }
            return state.chargeSeconds;
        }
    }

    /**
     * 判断玩家是否仍在按住指定按键。
     *
     * @param player 玩家
     * @param key 按键名
     * @return 正在按住返回 true
     */
    static boolean isPressing(Player player, String key) {
        PressState state = state(player, key);
        if (state == null) {
            return false;
        }
        synchronized (state) {
            return state.isPressing();
        }
    }

    /**
     * 获取当前蓄力秒数，按住时会包含本次按住的实时增量。
     *
     * @param player 玩家
     * @param key 按键名
     * @return 当前蓄力秒数
     */
    static double currentSeconds(Player player, String key) {
        PressState state = state(player, key);
        if (state == null) {
            return 0;
        }

        long now = System.currentTimeMillis();
        synchronized (state) {
            if (state.isPressing()) {
                return state.chargeSeconds + Math.max(0, (now - state.pressStartMillis) / 1000.0);
            }
            state.decay(now);
            return state.chargeSeconds;
        }
    }

    /**
     * 获取当前蓄力进度。
     *
     * @param player 玩家
     * @param key 按键名
     * @param requiredSeconds 需要蓄力的秒数
     * @return 0 到 1 之间的进度
     */
    static double progress(Player player, String key, double requiredSeconds) {
        if (requiredSeconds <= 0) {
            return 1;
        }
        return Math.max(0, Math.min(1, currentSeconds(player, key) / requiredSeconds));
    }

    /**
     * 注册按键蓄力任务，同一个玩家同一个按键只保留一个任务。
     *
     * @param player 玩家
     * @param key 按键名
     * @param task 任务
     */
    static void registerTask(Player player, String key, BukkitTask task) {
        if (player == null || key == null || task == null) {
            return;
        }

        String mapKey = mapKey(player.getUniqueId(), key);
        BukkitTask oldTask = TASK_MAP.put(mapKey, task);
        if (oldTask != null) {
            oldTask.cancel();
        }
    }

    /**
     * 判断指定按键是否已有蓄力任务。
     *
     * @param player 玩家
     * @param key 按键名
     * @return 有任务返回 true
     */
    static boolean hasTask(Player player, String key) {
        return player != null && key != null && TASK_MAP.containsKey(mapKey(player.getUniqueId(), key));
    }

    /**
     * 取消指定按键的蓄力任务，但不清理蓄力秒数。
     *
     * @param player 玩家
     * @param key 按键名
     */
    static void cancelTask(Player player, String key) {
        if (player == null || key == null) {
            return;
        }
        cancelTask(mapKey(player.getUniqueId(), key));
    }

    private static PressState state(Player player, String key) {
        if (player == null || key == null) {
            return null;
        }
        return PRESS_STATE_MAP.get(mapKey(player.getUniqueId(), key));
    }

    private static void cancelTask(String mapKey) {
        BukkitTask task = TASK_MAP.remove(mapKey);
        if (task != null) {
            task.cancel();
        }
    }

    private static String mapKey(UUID uuid, String key) {
        return uuid + ":" + KeySlotPotionHandler.normalizeKey(key).toUpperCase(Locale.ROOT);
    }

    /**
     * 单个玩家单个按键的蓄力状态。
     */
    private static final class PressState {
        private double chargeSeconds;
        private long pressStartMillis = -1;
        private long lastReleaseMillis;

        private PressState(long now) {
            this.lastReleaseMillis = now;
        }

        private boolean isPressing() {
            return pressStartMillis >= 0;
        }

        private void decay(long now) {
            if (isPressing()) {
                return;
            }
            double elapsedSeconds = Math.max(0, (now - lastReleaseMillis) / 1000.0);
            chargeSeconds = Math.max(0, chargeSeconds - elapsedSeconds);
            lastReleaseMillis = now;
        }
    }
}
