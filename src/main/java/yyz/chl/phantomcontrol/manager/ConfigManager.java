package yyz.chl.phantomcontrol.manager;

import org.bukkit.ChatColor;
import yyz.chl.phantomcontrol.PhantomControl;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.configuration.InvalidConfigurationException;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

public class ConfigManager {

    /** 当前配置文件版本。jar 内默认 config.yml / messages.yml 的 config-version 需与此一致。 */
    private static final int CONFIG_VERSION = 4;

    private final PhantomControl plugin;
    private volatile FileConfiguration config;
    private volatile FileConfiguration messagesConfig;
    private File messagesFile;
    private volatile String currentLanguage;
    private final Map<String, FileConfiguration> languageCache = new ConcurrentHashMap<>();

    public ConfigManager(PhantomControl plugin) {
        this.plugin = plugin;
        saveDefaultConfig();
        loadConfig();
    }

    private void saveDefaultConfig() {
        plugin.saveDefaultConfig();
        saveDefaultMessagesConfig();
    }

    private void saveDefaultMessagesConfig() {
        saveLanguageFile("messages.yml");
        saveLanguageFile("messages_en.yml");
    }

    private void saveLanguageFile(String fileName) {
        File file = new File(plugin.getDataFolder(), fileName);
        if (!file.exists()) {
            plugin.saveResource(fileName, false);
        }
    }

    /**
     * 首次加载配置（仅在插件启动时调用）。
     * 读取磁盘配置后进行版本迁移检查。
     */
    private void loadConfig() {
        reloadConfig();
    }

    /**
     * 热重载配置。先严格解析并校验磁盘文件，失败时保留当前运行配置。
     */
    public void reloadConfig() {
        File configFile = new File(plugin.getDataFolder(), "config.yml");
        YamlConfiguration candidate = loadYamlStrict(configFile, "config.yml");
        YamlConfiguration defaults = loadJarDefaults("config.yml");
        if (defaults != null) {
            candidate.setDefaults(defaults);
        }

        List<String> validationErrors = getValidationErrors(candidate);
        if (!validationErrors.isEmpty()) {
            throw new IllegalArgumentException(String.join("; ", validationErrors));
        }

        validateLanguageFiles(candidate);
        mergeDefaults(candidate, defaults, configFile);
        migrateMessagesConfig();
        this.config = candidate;
        reloadMessagesConfig();
    }

    public RuntimeConfigSnapshot snapshotRuntimeConfig() {
        return new RuntimeConfigSnapshot(config, messagesConfig, messagesFile, currentLanguage,
                Map.copyOf(languageCache));
    }

    public void restoreRuntimeConfig(RuntimeConfigSnapshot snapshot) {
        this.config = snapshot.config();
        this.messagesConfig = snapshot.messagesConfig();
        this.messagesFile = snapshot.messagesFile();
        this.currentLanguage = snapshot.currentLanguage();
        languageCache.clear();
        languageCache.putAll(snapshot.languageCache());
    }

    private YamlConfiguration loadYamlStrict(File file, String displayName) {
        YamlConfiguration yaml = new YamlConfiguration();
        try {
            yaml.load(file);
            return yaml;
        } catch (IOException | InvalidConfigurationException e) {
            throw new IllegalArgumentException(displayName + " YAML 格式错误: " + e.getMessage(), e);
        }
    }

    private void validateLanguageFiles(FileConfiguration candidate) {
        List<String> languageFiles = new ArrayList<>();
        languageFiles.add("messages.yml");
        languageFiles.add("messages_en.yml");

        String defaultLanguage = candidate.getString("settings.message.language.default", "messages_en");
        if (defaultLanguage != null && !defaultLanguage.isBlank()) {
            String fileName = defaultLanguage.endsWith(".yml")
                    ? defaultLanguage
                    : defaultLanguage + ".yml";
            if (!languageFiles.contains(fileName)) {
                languageFiles.add(fileName);
            }
        }

        for (String fileName : languageFiles) {
            File file = new File(plugin.getDataFolder(), fileName);
            if (file.exists()) {
                loadYamlStrict(file, fileName);
            }
        }
    }

    private void reloadMessagesConfig() {
        languageCache.clear();
        currentLanguage = config.getString("settings.message.language.default", "messages");
        languageCache.put("messages", loadLanguageFile("messages"));
        languageCache.put("messages_en", loadLanguageFile("messages_en"));
        this.messagesConfig = getCachedLanguageFile(currentLanguage);
        this.messagesFile = new File(plugin.getDataFolder(), currentLanguage + ".yml");
    }

    public String getCurrentLanguage() {
        return currentLanguage;
    }

    public boolean switchLanguage(String language) {
        File languageFile = new File(plugin.getDataFolder(), language + ".yml");

        if (languageFile.exists() || plugin.getResource(language + ".yml") != null) {
            config.set("settings.message.language.default", language);
            try {
                config.save(new File(plugin.getDataFolder(), "config.yml"));
            } catch (IOException e) {
                plugin.getLogger().warning("保存语言设置失败: " + e.getMessage());
                return false;
            }
            currentLanguage = language;
            languageCache.clear();
            this.messagesConfig = loadLanguageFile(currentLanguage);
            this.messagesFile = new File(plugin.getDataFolder(), currentLanguage + ".yml");
            return true;
        }

        return false;
    }

    public String getString(String path) {
        return config.getString(path);
    }

    public String getString(String path, String defaultValue) {
        return config.getString(path, defaultValue);
    }

    public int getInt(String path) {
        return config.getInt(path);
    }

    public long getLong(String path, long defaultValue) {
        return config.getLong(path, defaultValue);
    }

    public boolean getBoolean(String path) {
        return config.getBoolean(path);
    }

    public boolean getBoolean(String path, boolean defaultValue) {
        return config.getBoolean(path, defaultValue);
    }

    public List<String> getStringList(String path) {
        return config.getStringList(path);
    }

    public String getDatabaseType() {
        return getString("database.type", "flatfile").toLowerCase();
    }

    public String getMySQLAddress() {
        return getString("database.mysql.address");
    }

    public String getMySQLHost() {
        String address = getMySQLAddress();
        if (address == null || address.isBlank()) {
            return "localhost";
        }
        int separator = address.lastIndexOf(':');
        return separator > 0 ? address.substring(0, separator) : address;
    }

    public int getMySQLPort() {
        String address = getMySQLAddress();
        if (address != null) {
            int separator = address.lastIndexOf(':');
            if (separator > 0 && separator + 1 < address.length()) {
                try {
                    return Integer.parseInt(address.substring(separator + 1));
                } catch (NumberFormatException ignored) {
                    return 3306;
                }
            }
        }
        return 3306;
    }

    public String getMySQLDatabase() {
        return getString("database.mysql.database");
    }

    public String getMySQLUsername() {
        return getString("database.mysql.username");
    }

    public String getMySQLPassword() {
        return getString("database.mysql.password");
    }

    public String getMySQLPrefix() {
        return getString("database.mysql.prefix", "");
    }

    public int getAutoSaveInterval() {
        return getInt("database.auto-save-interval");
    }

    public String getMessage(String path) {
        String message = messagesConfig.getString(path);
        return message != null ? ChatColor.translateAlternateColorCodes('&', message) : null;
    }

    public String getMessage(String path, String defaultValue) {
        String message = messagesConfig.getString(path, defaultValue);
        return message != null ? ChatColor.translateAlternateColorCodes('&', message) : defaultValue;
    }

    public String getMessage(org.bukkit.entity.Player player, String path) {
        return getPlayerMessage(player, path, null);
    }

    public String getMessage(org.bukkit.entity.Player player, String path, String defaultValue) {
        return getPlayerMessage(player, path, defaultValue);
    }

    private String getPlayerMessage(org.bukkit.entity.Player player, String path, String defaultValue) {
        FileConfiguration playerConfig = getPlayerLanguageConfig(player);
        if (playerConfig != null) {
            String message = playerConfig.getString(path);
            if (message != null) {
                return ChatColor.translateAlternateColorCodes('&', message);
            }
        }
        return defaultValue != null ? getMessage(path, defaultValue) : getMessage(path);
    }

    public String formatMessage(String path, String... placeholderPairs) {
        String message = messagesConfig.getString(path);
        if (message == null) {
            return "";
        }
        return ChatColor.translateAlternateColorCodes('&', replacePlaceholders(message, placeholderPairs));
    }

    public String formatMessage(org.bukkit.entity.Player player, String path, String... placeholderPairs) {
        FileConfiguration playerConfig = getPlayerLanguageConfig(player);
        if (playerConfig != null) {
            String message = playerConfig.getString(path);
            if (message != null) {
                return ChatColor.translateAlternateColorCodes('&', replacePlaceholders(message, placeholderPairs));
            }
        }
        return formatMessage(path, placeholderPairs);
    }

    private String replacePlaceholders(String message, String... pairs) {
        for (int i = 0; i < pairs.length; i += 2) {
            message = message.replace(pairs[i], pairs[i + 1] != null ? pairs[i + 1] : "");
        }
        return message;
    }

    private FileConfiguration getPlayerLanguageConfig(org.bukkit.entity.Player player) {
        String languageMode = getString("settings.message.language.mode", "auto").toLowerCase(Locale.ROOT);
        String languageFile;

        switch (languageMode) {
            case "chinese":
                languageFile = "messages";
                break;
            case "english":
                languageFile = "messages_en";
                break;
            case "auto":
            default:
                if (player != null) {
                    String locale = player.getLocale();
                    if (locale != null && (locale.toLowerCase().startsWith("zh_cn") || locale.toLowerCase().startsWith("zh"))) {
                        languageFile = "messages";
                    } else {
                        languageFile = currentLanguage;
                    }
                } else {
                    languageFile = currentLanguage;
                }
                break;
        }

        return getCachedLanguageFile(languageFile);
    }

    private FileConfiguration getCachedLanguageFile(String languageFile) {
        if (languageCache.containsKey(languageFile)) {
            return languageCache.get(languageFile);
        }

        FileConfiguration config = loadLanguageFile(languageFile);
        languageCache.put(languageFile, config);
        return config;
    }

    private FileConfiguration loadLanguageFile(String languageFile) {
        File file = new File(plugin.getDataFolder(), languageFile + ".yml");
        if (file.exists()) {
            return loadYamlStrict(file, file.getName());
        }
        try (InputStream inputStream = plugin.getResource(languageFile + ".yml")) {
            if (inputStream != null) {
                return YamlConfiguration.loadConfiguration(
                        new InputStreamReader(inputStream, StandardCharsets.UTF_8));
            }
        } catch (IOException e) {
            plugin.getLogger().warning("无法加载语言文件: " + languageFile + ".yml");
        }
        return new YamlConfiguration();
    }

    public boolean isDebugEnabled() {
        return getBoolean("settings.debug.enabled");
    }

    public FileConfiguration getMessagesConfig() {
        return messagesConfig;
    }

    public File getMessagesFile() {
        return messagesFile;
    }

    public boolean validateConfig() {
        List<String> errors = getValidationErrors(config);
        errors.forEach(error -> plugin.getLogger().severe("配置错误: " + error));
        return errors.isEmpty();
    }

    private List<String> getValidationErrors(FileConfiguration candidate) {
        List<String> errors = new ArrayList<>();

        String databaseType = candidate.getString("database.type", "flatfile");
        databaseType = databaseType == null ? "" : databaseType.toLowerCase(Locale.ROOT);
        if (!databaseType.equals("flatfile") && !databaseType.equals("mysql")) {
            errors.add("数据库类型必须是 'flatfile' 或 'mysql'");
        }

        if (databaseType.equals("mysql")) {
            requireNonBlank(candidate, "database.mysql.address", "MySQL 地址不能为空", errors);
            requireNonBlank(candidate, "database.mysql.database", "MySQL 数据库名称不能为空", errors);
            requireNonBlank(candidate, "database.mysql.username", "MySQL 用户名不能为空", errors);
            requireNonBlank(candidate, "database.mysql.password", "MySQL 密码不能为空", errors);

            String prefix = candidate.getString("database.mysql.prefix", "");
            if (prefix == null || !prefix.matches("[A-Za-z0-9_]*")) {
                errors.add("MySQL 表前缀只能包含字母、数字和下划线");
            }

            String address = candidate.getString("database.mysql.address", "");
            int colon = address.lastIndexOf(':');
            if (colon >= 0) {
                try {
                    int port = Integer.parseInt(address.substring(colon + 1));
                    if (port < 1 || port > 65535) {
                        errors.add("MySQL 端口必须在 1 到 65535 之间");
                    }
                } catch (NumberFormatException e) {
                    errors.add("MySQL 地址端口格式无效");
                }
            }
        }

        requireNonNegativeNumber(candidate, "database.auto-save-interval", "自动保存间隔", errors);
        requireNonNegativeNumber(candidate, "database.cache-timeout-minutes", "缓存过期时间", errors);
        requireNonBlank(candidate, "settings.commands.main-command", "主命令名称不能为空", errors);
        requireNonBlank(candidate, "settings.commands.reload-command", "重载命令名称不能为空", errors);

        String languageMode = candidate.getString("settings.message.language.mode", "auto");
        if (languageMode == null || !List.of("auto", "chinese", "english")
                .contains(languageMode.toLowerCase(Locale.ROOT))) {
            errors.add("语言模式必须是 auto、chinese 或 english");
        }

        String defaultLanguage = candidate.getString("settings.message.language.default", "messages_en");
        if (defaultLanguage == null || !defaultLanguage.matches("[A-Za-z0-9_-]+")) {
            errors.add("默认语言文件名只能包含字母、数字、下划线和连字符，且不含 .yml");
        }

        String messageType = candidate.getString("settings.message.default-type", "CHAT");
        if (messageType == null || !List.of("CHAT", "ACTION_BAR", "TITLE")
                .contains(messageType.toUpperCase(Locale.ROOT))) {
            errors.add("默认消息显示方式必须是 CHAT、ACTION_BAR 或 TITLE");
        }

        return errors;
    }

    private void requireNonBlank(FileConfiguration candidate, String path, String error, List<String> errors) {
        String value = candidate.getString(path);
        if (value == null || value.isBlank()) {
            errors.add(error);
        }
    }

    private void requireNonNegativeNumber(FileConfiguration candidate, String path, String label,
                                          List<String> errors) {
        Object value = candidate.get(path);
        if (!(value instanceof Number number)) {
            errors.add(label + "必须是数字");
        } else if (number.longValue() < 0) {
            errors.add(label + "不能为负数");
        }
    }

    // Merge by complete YAML paths, never by matching indentation or neighboring keys.
    // Bukkit preserves user comments; missing values receive their default comments.
    private void mergeDefaults(YamlConfiguration target, YamlConfiguration defaults, File file) {
        if (defaults == null) return;
        boolean changed = false;
        for (String path : defaults.getKeys(true)) {
            if (target.isSet(path)) continue;
            // Do not replace a user's scalar parent with a section.
            int dot = path.lastIndexOf('.');
            if (dot >= 0 && !target.isConfigurationSection(path.substring(0, dot))) continue;
            if (defaults.isConfigurationSection(path)) target.createSection(path);
            else target.set(path, defaults.get(path));
            target.setComments(path, defaults.getComments(path));
            target.setInlineComments(path, defaults.getInlineComments(path));
            changed = true;
        }
        if (target.getInt("config-version") < CONFIG_VERSION) {
            target.set("config-version", CONFIG_VERSION);
            changed = true;
        }
        if (changed) {
            try { target.save(file); }
            catch (IOException error) { throw new IllegalStateException("无法升级配置: " + file.getName(), error); }
        }
    }

    private void migrateMessagesConfig() {
        for (String name : List.of("messages.yml", "messages_en.yml")) {
            File file = new File(plugin.getDataFolder(), name);
            mergeDefaults(loadYamlStrict(file, name), loadJarDefaults(name), file);
        }
    }

    private YamlConfiguration loadJarDefaults(String name) {
        try (InputStream input = plugin.getResource(name)) {
            if (input == null) return null;
            return YamlConfiguration.loadConfiguration(new InputStreamReader(input, StandardCharsets.UTF_8));
        } catch (IOException error) {
            throw new IllegalStateException("无法读取默认配置: " + name, error);
        }
    }

    public record RuntimeConfigSnapshot(FileConfiguration config, FileConfiguration messagesConfig,
                                        File messagesFile, String currentLanguage,
                                        Map<String, FileConfiguration> languageCache) {
    }
}
