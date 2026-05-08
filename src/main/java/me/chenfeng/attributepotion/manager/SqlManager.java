package me.chenfeng.attributepotion.manager;

import com.google.gson.Gson;
import com.google.gson.JsonSyntaxException;
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

public class SqlManager {
    private static final Gson GSON = new Gson();

    private final AttributePotion plugin;
    private HikariDataSource dataSource;
    private boolean mysql;
    private String legacyTableName;
    private String profileTableName;

    public SqlManager(AttributePotion plugin) {
        this.plugin = plugin;
    }

    public void init() {
        loadDataSource();
        createTables();
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
            String driver = config.getString("mysql.driver", config.getString("MySQL.driver", "com.mysql.cj.jdbc.Driver"));
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
            statement.executeUpdate("CREATE TABLE IF NOT EXISTS " + profileTableName + " ("
                    + "uuid VARCHAR(36) PRIMARY KEY, "
                    + "profile_json " + textType() + " NOT NULL, "
                    + "updated_at BIGINT NOT NULL)");

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

    public void loadProfileAsync(Player player) {
        UUID uuid = player.getUniqueId();
        Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
            StoredProfile storedProfile = loadStoredProfile(uuid);
            Bukkit.getScheduler().runTaskLater(plugin,
                    () -> applyStoredProfile(player, storedProfile),
                    Math.max(0, ConfigManager.getLoadDelay()));
        });
    }

    public void saveProfileAsync(UUID uuid, PlayerProfile profile) {
        StoredProfile snapshot = toStoredProfile(profile);
        Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> saveStoredProfile(uuid, snapshot));
    }

    public void saveProfileBlocking(UUID uuid, PlayerProfile profile) {
        saveStoredProfile(uuid, toStoredProfile(profile));
    }

    public void close() {
        if (dataSource != null) {
            dataSource.close();
        }
    }

    private Connection getConnection() throws SQLException {
        if (dataSource == null) {
            throw new IllegalStateException("Database is not initialized");
        }
        return dataSource.getConnection();
    }

    private StoredProfile loadStoredProfile(UUID uuid) {
        StoredProfile profile = loadNewProfile(uuid);
        if (profile != null) {
            return profile;
        }
        return loadLegacyProfile(uuid);
    }

    private StoredProfile loadNewProfile(UUID uuid) {
        String sql = "SELECT profile_json FROM " + profileTableName + " WHERE uuid = ?";
        try (Connection connection = getConnection();
             PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, uuid.toString());
            try (ResultSet resultSet = statement.executeQuery()) {
                if (!resultSet.next()) {
                    return null;
                }
                return normalizeStoredProfile(GSON.fromJson(resultSet.getString("profile_json"), StoredProfile.class));
            }
        } catch (SQLException | JsonSyntaxException e) {
            LoggerUtil.warning("[AttributePotion] Failed to load profile " + uuid + ": " + e.getMessage());
            return null;
        }
    }

    private StoredProfile loadLegacyProfile(UUID uuid) {
        StoredProfile profile = new StoredProfile();
        long now = System.currentTimeMillis();

        try (Connection connection = getConnection()) {
            loadLegacyActivePotions(connection, uuid, profile, now);
            loadLegacyCooldowns(connection, uuid, profile, now);
        } catch (SQLException e) {
            LoggerUtil.warning("[AttributePotion] Failed to load legacy profile " + uuid + ": " + e.getMessage());
        }

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

    private void applyStoredProfile(Player player, StoredProfile storedProfile) {
        if (storedProfile == null || !player.isOnline()) {
            return;
        }

        PlayerProfile profile = PlayerManager.getOrCreateProfile(player);
        long now = System.currentTimeMillis();

        putUnexpired(profile.getPotionCooldowns(), storedProfile.potionCooldowns, now);
        putUnexpired(profile.getGroupCooldowns(), storedProfile.groupCooldowns, now);

        for (StoredPotion storedPotion : storedProfile.activePotions) {
            if (storedPotion == null || storedPotion.key == null) {
                continue;
            }
            if (storedPotion.endTime != Long.MAX_VALUE && storedPotion.endTime <= now) {
                continue;
            }
            ActivePotion activePotion = new ActivePotion(
                    storedPotion.key,
                    player,
                    storedPotion.useTime,
                    storedPotion.endTime,
                    storedPotion.attributes);
            if (!profile.getActivePotions().containsKey(storedPotion.key)) {
                PlayerManager.restoreActivePotion(player, profile, activePotion);
            }
        }
    }

    private StoredProfile toStoredProfile(PlayerProfile profile) {
        StoredProfile storedProfile = new StoredProfile();
        long now = System.currentTimeMillis();

        for (Map.Entry<String, ActivePotion> entry : profile.getActivePotions().entrySet()) {
            ActivePotion activePotion = entry.getValue();
            if (activePotion == null || activePotion.isExpired()) {
                continue;
            }
            StoredPotion storedPotion = new StoredPotion();
            storedPotion.key = entry.getKey();
            storedPotion.useTime = activePotion.getUseTime();
            storedPotion.endTime = activePotion.getEndTime();
            storedPotion.attributes = new ArrayList<>(activePotion.getAppliedAttributes());
            storedProfile.activePotions.add(storedPotion);
        }

        putUnexpired(storedProfile.potionCooldowns, profile.getPotionCooldowns(), now);
        putUnexpired(storedProfile.groupCooldowns, profile.getGroupCooldowns(), now);
        return storedProfile;
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
