package me.chenfeng.attributepotion.manager;

import com.google.gson.Gson;
import com.google.gson.JsonParseException;
import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import me.chenfeng.attributepotion.AttributePotion;
import me.chenfeng.attributepotion.data.ActivePotion;
import me.chenfeng.attributepotion.data.PlayerProfile;
import me.chenfeng.attributepotion.data.PotionConfig;
import me.chenfeng.attributepotion.utils.LoggerUtil;
import org.bukkit.Bukkit;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.entity.Player;

import java.io.File;
import java.sql.*;
import java.util.*;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

public class SqlManager {
    private static final Gson GSON = new Gson();

    private final AttributePotion plugin;
    private HikariDataSource dataSource;
    private boolean mysql;
    private String legacyTableName;
    private String profileTableName;
    /**
     * 所有数据库读写都排进这一条线程，保证同一玩家“退出存档”一定先于“再次登录读取”。
     */
    private final ExecutorService executor = Executors.newSingleThreadExecutor(runnable -> {
        Thread thread = new Thread(runnable, "AttributePotion-Database");
        thread.setDaemon(true);
        return thread;
    });

    public SqlManager(AttributePotion plugin) {
        this.plugin = plugin;
    }

    /**
     * 连接数据库。连接失败时只记录错误，插件继续运行但不读写数据：药水功能可用，玩家数据不落库。
     */
    public void init() {
        try {
            loadDataSource();
            createTables();
        } catch (RuntimeException | LinkageError e) {
            LoggerUtil.severe("[AttributePotion] 数据库不可用，本次运行不会读取或保存玩家数据，请检查 mysql 配置: " + e);
            close();
            dataSource = null;
        }
    }

    public boolean isAvailable() {
        return dataSource != null && !dataSource.isClosed();
    }

    private void loadDataSource() {
        FileConfiguration config = plugin.getConfig();
        mysql = config.getBoolean("mysql.enable", config.getBoolean("MySQL.enable", false));

        String database = resolveDatabaseName(config);
        legacyTableName = sanitizeIdentifier(config.getString("mysql.table_name", config.getString("MySQL.tableName", "data")));
        profileTableName = legacyTableName + "_profile";

        HikariConfig hikariConfig = new HikariConfig();
        hikariConfig.setPoolName("AttributePotion");
        int maximumPoolSize = config.getInt("mysql.pool.maximum-pool-size", 10);
        int minimumIdle = config.getInt("mysql.pool.minimum-idle", 2);
        hikariConfig.setMaximumPoolSize(maximumPoolSize);
        hikariConfig.setMinimumIdle(minimumIdle);
        hikariConfig.setConnectionTimeout(config.getLong("mysql.pool.connection-timeout", 30000));
        hikariConfig.setMaxLifetime(config.getLong("mysql.pool.max-lifetime", 1800000));

        if (mysql) {
            String host = config.getString("mysql.host", config.getString("MySQL.host", "localhost"));
            int port = config.getInt("mysql.port", config.getInt("MySQL.port", 3306));
            String driver = config.getString("mysql.driver", config.getString("MySQL.driver", "com.mysql.jdbc.Driver"));
            String jdbc = config.getString("mysql.jdbc", config.getString("MySQL.jdbc", "?useSSL=false&useUnicode=true&characterEncoding=utf8"));
            hikariConfig.setDriverClassName(driver);
            hikariConfig.setJdbcUrl("jdbc:mysql://" + host + ":" + port + "/" + database + jdbc);
            hikariConfig.setUsername(config.getString("mysql.username", config.getString("MySQL.username", "root")));
            hikariConfig.setPassword(config.getString("mysql.password", config.getString("MySQL.password", "")));
            if (minimumIdle < maximumPoolSize) {
                hikariConfig.setIdleTimeout(config.getLong("mysql.pool.idle-timeout", 30000));
            }
        } else {
            File databaseFile = new File(plugin.getDataFolder(), database.endsWith(".db") ? database : database + ".db");
            hikariConfig.setDriverClassName("org.sqlite.JDBC");
            hikariConfig.setJdbcUrl("jdbc:sqlite:" + databaseFile.getAbsolutePath());
            hikariConfig.setMaximumPoolSize(1);
            hikariConfig.setMinimumIdle(1);
        }

        dataSource = new HikariDataSource(hikariConfig);
        LoggerUtil.info("[AttributePotion] Database connected: " + (mysql ? "MySQL" : "SQLite"));
    }

    /**
     * 解析数据库名称。
     * <p>
     * 新版配置使用 mysql.file_name，旧版配置使用 MySQL.fileName。
     * 如果 SQLite 没有显式配置文件名，并且插件目录下存在旧版 database.db，则优先读取旧文件，
     * 避免升级后因为默认文件名变化导致旧数据无法读取。
     */
    private String resolveDatabaseName(FileConfiguration config) {
        if (config.contains("mysql.file_name")) {
            return config.getString("mysql.file_name", "AttributePotion");
        }
        if (config.contains("MySQL.fileName")) {
            return config.getString("MySQL.fileName", "database");
        }
        if (!mysql && new File(plugin.getDataFolder(), "database.db").exists()) {
            return "database";
        }
        return "AttributePotion";
    }

    private void createTables() {
        try (Connection connection = getConnection();
             Statement statement = connection.createStatement()) {
            // 药水节点通常是中文，MySQL 库默认字符集是 latin1 时不显式指定就会存成问号
            statement.executeUpdate("CREATE TABLE IF NOT EXISTS " + profileTableName + " ("
                    + "uuid VARCHAR(36) PRIMARY KEY, "
                    + "profile_json " + textType() + " NOT NULL, "
                    + "updated_at BIGINT NOT NULL)"
                    + (mysql ? " DEFAULT CHARSET=utf8mb4" : ""));

            statement.executeUpdate("CREATE TABLE IF NOT EXISTS " + legacyTableName + " ("
                    + "uuid VARCHAR(36), "
                    + "potionKey VARCHAR(255), "
                    + "attrList VARCHAR(3000), "
                    + "useTime BIGINT, "
                    + "endTime BIGINT)");

            statement.executeUpdate("CREATE TABLE IF NOT EXISTS time_record ("
                    + "uuid VARCHAR(36) PRIMARY KEY, "
                    + "group_record VARCHAR(1000), "
                    + "potion_record VARCHAR(1000))");
        } catch (SQLException e) {
            throw new IllegalStateException("Failed to create database tables", e);
        }
    }

    /**
     * 异步读取玩家档案。
     * <p>
     * 读取返回后立刻合并冷却并放行药水使用；激活中的药水要等 load-delay 后再恢复，给属性插件留出初始化时间。
     * 这段时间内它们放在 pendingPotions 里，玩家此时退出也会被正常存回去。
     */
    public void loadProfileAsync(Player player) {
        UUID uuid = player.getUniqueId();
        if (!isAvailable()) {
            PlayerProfile profile = PlayerManager.getProfile(uuid);
            if (profile != null) {
                profile.setDataLoaded(true);
            }
            return;
        }

        executor.execute(() -> {
            StoredProfile storedProfile = null;
            boolean success = true;
            try {
                storedProfile = loadStoredProfile(uuid);
            } catch (Exception e) {
                success = false;
                LoggerUtil.warning("[AttributePotion] 读取玩家 " + uuid + " 的数据失败，本次在线期间不会覆盖其存档: " + e.getMessage());
            }

            StoredProfile loaded = storedProfile;
            boolean loadSuccess = success;
            runSync(() -> applyStoredProfile(player, loaded, loadSuccess));
        });
    }

    public void saveProfileAsync(UUID uuid, PlayerProfile profile) {
        if (!isAvailable() || !profile.isSaveAllowed()) {
            return;
        }
        StoredProfile snapshot = toStoredProfile(profile);
        executor.execute(() -> saveStoredProfile(uuid, snapshot));
    }

    /**
     * 异步保存所有在线玩家的档案，用于定时存档。
     */
    public void saveAllAsync() {
        PlayerManager.getAllProfiles().forEach(this::saveProfileAsync);
    }

    public void saveProfileBlocking(UUID uuid, PlayerProfile profile) {
        if (!isAvailable() || !profile.isSaveAllowed()) {
            return;
        }
        saveStoredProfile(uuid, toStoredProfile(profile));
    }

    /**
     * 等待队列里的读写全部完成。关服时要先调用它，再做最后一次同步存档。
     */
    public void awaitPendingTasks() {
        executor.shutdown();
        try {
            if (!executor.awaitTermination(15, TimeUnit.SECONDS)) {
                LoggerUtil.warning("[AttributePotion] 等待数据库队列超时，可能有数据未写入");
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    public void close() {
        if (dataSource != null) {
            dataSource.close();
        }
    }

    private void runSync(Runnable runnable) {
        if (plugin.isEnabled()) {
            Bukkit.getScheduler().runTask(plugin, runnable);
        }
    }

    private Connection getConnection() throws SQLException {
        if (dataSource == null) {
            throw new IllegalStateException("Database is not initialized");
        }
        return dataSource.getConnection();
    }

    /**
     * @return 玩家档案，没有任何记录时返回 null
     * @throws SQLException 数据库读取失败
     * @throws JsonParseException 档案 JSON 损坏
     */
    private StoredProfile loadStoredProfile(UUID uuid) throws SQLException {
        try (Connection connection = getConnection()) {
            StoredProfile profile = loadNewProfile(connection, uuid);
            return profile != null ? profile : loadLegacyProfile(connection, uuid);
        }
    }

    private StoredProfile loadNewProfile(Connection connection, UUID uuid) throws SQLException {
        String sql = "SELECT profile_json FROM " + profileTableName + " WHERE uuid = ?";
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, uuid.toString());
            try (ResultSet resultSet = statement.executeQuery()) {
                if (!resultSet.next()) {
                    return null;
                }
                return normalizeStoredProfile(GSON.fromJson(resultSet.getString("profile_json"), StoredProfile.class));
            }
        }
    }

    private StoredProfile loadLegacyProfile(Connection connection, UUID uuid) throws SQLException {
        StoredProfile profile = new StoredProfile();
        long now = System.currentTimeMillis();
        loadLegacyActivePotions(connection, uuid, profile, now);
        loadLegacyCooldowns(connection, uuid, profile, now);
        return profile.isEmpty() ? null : profile;
    }

    private void loadLegacyActivePotions(Connection connection, UUID uuid, StoredProfile profile, long now) throws SQLException {
        String sql = "SELECT potionKey, attrList, useTime, endTime FROM " + legacyTableName + " WHERE uuid = ?";
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, uuid.toString());
            try (ResultSet resultSet = statement.executeQuery()) {
                while (resultSet.next()) {
                    String potionKey = resultSet.getString("potionKey");
                    PotionConfig config = ConfigManager.getPotionConfig(potionKey);
                    if (config == null) {
                        continue;
                    }

                    long useTime = resultSet.getLong("useTime");
                    long endTime = resultSet.getLong("endTime");
                    if (config.getTime() >= 0 && endTime > 0 && now >= endTime) {
                        continue;
                    }

                    StoredPotion potion = new StoredPotion();
                    potion.key = potionKey;
                    potion.useTime = useTime;
                    potion.endTime = config.getTime() < 0 ? Long.MAX_VALUE : endTime;
                    potion.attributes = splitAttributes(resultSet.getString("attrList"));
                    profile.activePotions.add(potion);
                }
            }
        }
    }

    private void loadLegacyCooldowns(Connection connection, UUID uuid, StoredProfile profile, long now) throws SQLException {
        String sql = "SELECT group_record, potion_record FROM time_record WHERE uuid = ?";
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, uuid.toString());
            try (ResultSet resultSet = statement.executeQuery()) {
                if (!resultSet.next()) {
                    return;
                }

                Map<String, Long> legacyPotionTimes = parseLegacyMap(resultSet.getString("potion_record"));
                for (Map.Entry<String, Long> entry : legacyPotionTimes.entrySet()) {
                    PotionConfig config = ConfigManager.getPotionConfig(entry.getKey());
                    if (config == null || config.getCooldown() <= 0) {
                        continue;
                    }
                    long expiresAt = entry.getValue() + (long) (config.getCooldown() * 1000);
                    if (expiresAt > now) {
                        profile.potionCooldowns.put(entry.getKey(), expiresAt);
                    }
                }

                Map<String, Long> legacyGroupTimes = parseLegacyMap(resultSet.getString("group_record"));
                for (Map.Entry<String, Long> entry : legacyGroupTimes.entrySet()) {
                    double cooldown = ConfigManager.getGroupCooldown(entry.getKey());
                    if (cooldown <= 0) {
                        continue;
                    }
                    long expiresAt = entry.getValue() + (long) (cooldown * 1000);
                    if (expiresAt > now) {
                        profile.groupCooldowns.put(entry.getKey(), expiresAt);
                    }
                }
            }
        }
    }

    private void saveStoredProfile(UUID uuid, StoredProfile profile) {
        String json = GSON.toJson(profile);
        try (Connection connection = getConnection()) {
            if (mysql) {
                saveMysqlProfile(connection, uuid, json);
            } else {
                saveSqliteProfile(connection, uuid, json);
            }
        } catch (SQLException e) {
            LoggerUtil.warning("[AttributePotion] Failed to save profile " + uuid + ": " + e.getMessage());
        }
    }

    private void saveMysqlProfile(Connection connection, UUID uuid, String json) throws SQLException {
        String sql = "INSERT INTO " + profileTableName + " (uuid, profile_json, updated_at) VALUES (?, ?, ?) "
                + "ON DUPLICATE KEY UPDATE profile_json = VALUES(profile_json), updated_at = VALUES(updated_at)";

        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, uuid.toString());
            statement.setString(2, json);
            statement.setLong(3, System.currentTimeMillis());
            statement.executeUpdate();
        }
    }

    private void saveSqliteProfile(Connection connection, UUID uuid, String json) throws SQLException {
        long now = System.currentTimeMillis();
        String updateSql = "UPDATE " + profileTableName + " SET profile_json = ?, updated_at = ? WHERE uuid = ?";
        try (PreparedStatement statement = connection.prepareStatement(updateSql)) {
            statement.setString(1, json);
            statement.setLong(2, now);
            statement.setString(3, uuid.toString());
            if (statement.executeUpdate() > 0) {
                return;
            }
        }

        String insertSql = "INSERT INTO " + profileTableName + " (uuid, profile_json, updated_at) VALUES (?, ?, ?)";
        try (PreparedStatement statement = connection.prepareStatement(insertSql)) {
            statement.setString(1, uuid.toString());
            statement.setString(2, json);
            statement.setLong(3, now);
            statement.executeUpdate();
        }
    }

    private void applyStoredProfile(Player player, StoredProfile storedProfile, boolean success) {
        UUID uuid = player.getUniqueId();
        // 玩家已下线或已重新登录时，player 是旧对象，这份结果必须丢弃，新登录有自己的读取任务
        PlayerProfile profile = PlayerManager.getProfile(uuid);
        if (profile == null || Bukkit.getPlayer(uuid) != player) {
            return;
        }

        if (!success) {
            profile.setDataLoaded(true);
            return;
        }

        profile.setSaveAllowed(true);
        boolean hasData = false;
        if (storedProfile != null) {
            long now = System.currentTimeMillis();
            putUnexpired(profile.getPotionCooldowns(), storedProfile.potionCooldowns, now);
            putUnexpired(profile.getGroupCooldowns(), storedProfile.groupCooldowns, now);
            for (StoredPotion storedPotion : storedProfile.activePotions) {
                if (storedPotion != null && storedPotion.key != null) {
                    profile.getPendingPotions().add(new ActivePotion(
                            storedPotion.key,
                            player,
                            storedPotion.useTime,
                            storedPotion.endTime,
                            storedPotion.attributes));
                }
            }
            hasData = !storedProfile.isEmpty();
        }
        profile.setDataLoaded(true);

        boolean notify = hasData;
        Bukkit.getScheduler().runTaskLater(plugin,
                () -> restorePendingPotions(player, profile, notify),
                Math.max(0, ConfigManager.getLoadDelay()));
    }

    private void restorePendingPotions(Player player, PlayerProfile profile, boolean notify) {
        if (PlayerManager.getProfile(player) != profile || Bukkit.getPlayer(player.getUniqueId()) != player) {
            return;
        }

        List<ActivePotion> pending = new ArrayList<>(profile.getPendingPotions());
        profile.getPendingPotions().clear();
        for (ActivePotion activePotion : pending) {
            if (activePotion.isExpired()) {
                PlayerManager.finishOfflineExpiredPotion(player, activePotion);
            } else if (!profile.getActivePotions().containsKey(activePotion.getPotionKey())) {
                PlayerManager.restoreActivePotion(player, profile, activePotion);
            }
        }

        if (notify) {
            ConfigManager.sendMessage(player, "load-data", null);
        }
    }

    private StoredProfile toStoredProfile(PlayerProfile profile) {
        StoredProfile storedProfile = new StoredProfile();
        long now = System.currentTimeMillis();

        // 已到期但还没结算的药水也要存，下次登录时才能补上 end 动作
        for (ActivePotion activePotion : profile.getActivePotions().values()) {
            if (activePotion != null) {
                storedProfile.activePotions.add(toStoredPotion(activePotion));
            }
        }
        for (ActivePotion activePotion : profile.getPendingPotions()) {
            storedProfile.activePotions.add(toStoredPotion(activePotion));
        }

        putUnexpired(storedProfile.potionCooldowns, profile.getPotionCooldowns(), now);
        putUnexpired(storedProfile.groupCooldowns, profile.getGroupCooldowns(), now);
        return storedProfile;
    }

    private StoredPotion toStoredPotion(ActivePotion activePotion) {
        StoredPotion storedPotion = new StoredPotion();
        storedPotion.key = activePotion.getPotionKey();
        storedPotion.useTime = activePotion.getUseTime();
        storedPotion.endTime = activePotion.getEndTime();
        storedPotion.attributes = new ArrayList<>(activePotion.getAppliedAttributes());
        return storedPotion;
    }

    private void putUnexpired(Map<String, Long> target, Map<String, Long> source, long now) {
        if (source == null) {
            return;
        }
        for (Map.Entry<String, Long> entry : source.entrySet()) {
            Long expiresAt = entry.getValue();
            if (entry.getKey() != null && expiresAt != null && expiresAt > now) {
                target.merge(entry.getKey(), expiresAt, Math::max);
            }
        }
    }

    private StoredProfile normalizeStoredProfile(StoredProfile profile) {
        if (profile == null) {
            return null;
        }
        if (profile.activePotions == null) {
            profile.activePotions = new ArrayList<>();
        }
        if (profile.potionCooldowns == null) {
            profile.potionCooldowns = new LinkedHashMap<>();
        }
        if (profile.groupCooldowns == null) {
            profile.groupCooldowns = new LinkedHashMap<>();
        }
        return profile;
    }

    private List<String> splitAttributes(String attrList) {
        if (attrList == null || attrList.trim().isEmpty()) {
            return Collections.emptyList();
        }
        return new ArrayList<>(Arrays.asList(attrList.split(",")));
    }

    private Map<String, Long> parseLegacyMap(String raw) {
        Map<String, Long> map = new LinkedHashMap<>();
        if (raw == null || raw.length() < 2) {
            return map;
        }

        String content = raw.substring(1, raw.length() - 1).trim();
        if (content.isEmpty()) {
            return map;
        }

        for (String entry : content.split(", ")) {
            String[] parts = entry.split("=", 2);
            if (parts.length != 2) {
                continue;
            }
            try {
                map.put(parts[0], Long.parseLong(parts[1]));
            } catch (NumberFormatException ignored) {
            }
        }
        return map;
    }

    private String textType() {
        return mysql ? "MEDIUMTEXT" : "TEXT";
    }

    private String sanitizeIdentifier(String value) {
        if (value == null || value.trim().isEmpty()) {
            return "data";
        }
        return value.replaceAll("[^A-Za-z0-9_]", "_");
    }

    private static class StoredProfile {
        private int version = 2;
        private List<StoredPotion> activePotions = new ArrayList<>();
        private Map<String, Long> potionCooldowns = new LinkedHashMap<>();
        private Map<String, Long> groupCooldowns = new LinkedHashMap<>();

        private boolean isEmpty() {
            return activePotions.isEmpty() && potionCooldowns.isEmpty() && groupCooldowns.isEmpty();
        }
    }

    private static class StoredPotion {
        private String key;
        private long useTime;
        private long endTime;
        private List<String> attributes = new ArrayList<>();
    }
}
