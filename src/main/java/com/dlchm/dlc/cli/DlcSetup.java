package com.dlchm.dlc.cli;

import com.dlchm.dlc.config.DlcProperties;

import java.io.*;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Properties;

/**
 * 首次启动配置引导，在 Spring 上下文初始化之前运行。
 * 配置持久化到 ~/.dlc/config.properties
 */
public class DlcSetup {

    // Empty on a terminal that cannot render escapes; see AnsiSupport.
    private static final String ANSI_RESET = AnsiSupport.ansi("0m");
    private static final String ANSI_CYAN = AnsiSupport.ansi("36m");
    private static final String ANSI_DIM = AnsiSupport.ansi("2m");
    private static final String ANSI_GREEN = AnsiSupport.ansi("32m");
    private static final String ANSI_YELLOW = AnsiSupport.ansi("33m");

    private static final Path CONFIG_DIR = Path.of(System.getProperty("user.home"), ".dlc");
    private static final Path CONFIG_FILE = CONFIG_DIR.resolve("config.properties");

    private static final String KEY_BASE_URL = "base-url";
    private static final String KEY_API_KEY = "api-key";
    private static final String KEY_MODEL = "model";
    private static final String KEY_SUBAGENT_ENABLED = "subagent-enabled";
    private static final String KEY_SUBAGENT_MAX_DEPTH = "subagent-max-depth";
    private static final String KEY_SUBAGENT_MAX_CONCURRENT = "subagent-max-concurrent";
    private static final String KEY_SUBAGENT_TIMEOUT = "subagent-timeout";
    private static final String KEY_SUBAGENT_MAX_TIMEOUT = "subagent-max-timeout";

    /**
     * 每项：配置键、提示标签、中文说明、默认值。
     * 说明会同时打印在交互界面和落盘的 config.properties 里，两处不会漂移。
     */
    private record Field(String key, String label, String comment, String def) {}

    private static final Field[] SUBAGENT_FIELDS = {
            new Field(KEY_SUBAGENT_ENABLED, "启用子 Agent (true/false)",
                    "是否启用子 Agent（子代理委派能力）。false = 完全关闭，模型看不到委派工具，/sub 命令也会拒绝",
                    "true"),
            new Field(KEY_SUBAGENT_MAX_DEPTH, "最大嵌套深度 (整数)",
                    "子 Agent 还能继续派发几层。1 = 子 Agent 不能再往下派；不建议调高，递归委派会迅速失控",
                    "1"),
            new Field(KEY_SUBAGENT_MAX_CONCURRENT, "并发上限 (整数)",
                    "同一时间最多并行运行多少个子 Agent。调高会成倍消耗 API 配额",
                    "4"),
            new Field(KEY_SUBAGENT_TIMEOUT, "默认超时 (秒)",
                    "未指定时子 Agent 最多运行多久。超时后会返回明确原因，不会静默挂住",
                    "300"),
            new Field(KEY_SUBAGENT_MAX_TIMEOUT, "超时硬上限 (秒)",
                    "调用方传入的超时被强制压到不超过此值，防止一次委派长期占用线程池",
                    "600"),
    };

    /**
     * 检查配置是否存在，不存在则引导用户配置。
     * 将配置设置为系统属性，供 Spring Boot 读取。
     */
    public static void ensureConfigured() {
        String envUrl = System.getenv("SPRING_AI_OPENAI_BASE_URL");
        String envKey = System.getenv("SPRING_AI_OPENAI_API_KEY");
        String envModel = System.getenv("SPRING_AI_OPENAI_CHAT_OPTIONS_MODEL");
        if (envUrl != null && !envUrl.isBlank() && envKey != null && !envKey.isBlank()
                && envModel != null && !envModel.isBlank()) {
            System.setProperty("spring.ai.openai.base-url", envUrl);
            System.setProperty("spring.ai.openai.api-key", envKey);
            System.setProperty("spring.ai.openai.chat.options.model", envModel);
            return;
        }
        Properties config = loadConfig();

        if (config.isEmpty()
                || !config.containsKey(KEY_BASE_URL) || config.getProperty(KEY_BASE_URL).isBlank()
                || !config.containsKey(KEY_API_KEY) || config.getProperty(KEY_API_KEY).isBlank()) {
            config = runSetupWizard(config);
            saveConfig(config);
        }

        // Set as system properties so Spring picks them up
        applyToSystemProperties(config);
    }

    /**
     * 强制重新配置（/config 命令）。
     *
     * @return 重新配置后的属性，供调用方同步到运行中的 bean
     */
    public static Properties reconfigure() {
        Properties config = loadConfig();
        config = runSetupWizard(config);
        saveConfig(config);
        applyToSystemProperties(config);
        return config;
    }

    public static boolean isConfigured() {
        Properties config = loadConfig();
        return config.containsKey(KEY_API_KEY) && !config.getProperty(KEY_API_KEY).isBlank();
    }

    private static Properties loadConfig() {
        Properties props = new Properties();
        if (Files.exists(CONFIG_FILE)) {
            try (Reader reader = Files.newBufferedReader(CONFIG_FILE)) {
                props.load(reader);
            } catch (IOException e) {
                // Ignore, will re-create
            }
        }
        return props;
    }

    private static void saveConfig(Properties config) {
        try {
            Files.createDirectories(CONFIG_DIR);
            try (Writer writer = Files.newBufferedWriter(CONFIG_FILE)) {
                writeAnnotated(writer, config);
            }
            // Restrict file permissions (owner only)
            CONFIG_FILE.toFile().setReadable(false, false);
            CONFIG_FILE.toFile().setReadable(true, true);
            CONFIG_FILE.toFile().setWritable(false, false);
            CONFIG_FILE.toFile().setWritable(true, true);
        } catch (IOException e) {
            System.err.println(ANSI_YELLOW + "Warning: Failed to save config: " + e.getMessage() + ANSI_RESET);
        }
    }

    /**
     * Serialises the config with a Chinese comment above every key.
     *
     * <p>{@code Properties.store} can only take one file-level comment, which
     * leaves a bare wall of keys with no explanation. Since the whole point of
     * persisting this file is that a human can edit it later, each key gets its
     * own line of documentation at the point of use.</p>
     */
    private static void writeAnnotated(Writer writer, Properties config) throws IOException {
        writer.write("# Polar 配置\n");
        writer.write("# 由 /config 命令生成，可直接编辑后重启生效。\n");
        writer.write("#\n");
        writeField(writer, config, KEY_BASE_URL, "模型服务地址，OpenAI 兼容接口");
        writeField(writer, config, KEY_API_KEY, "API 密钥，保存后本文件权限收紧为仅当前用户可读写");
        writeField(writer, config, KEY_MODEL, "使用的模型名称");
        writer.write("\n# ── 子 Agent（Sub Agent）──\n");
        writer.write("# 子 Agent 用独立会话并行执行独立任务，只把最终总结回传给主会话。\n");
        for (Field field : SUBAGENT_FIELDS) {
            writeField(writer, config, field.key(), field.comment());
        }
    }

    /**
     * Exposes {@link #writeAnnotated} so the annotation format can be asserted
     * without writing to the real {@code ~/.dlc/config.properties}.
     */
    public static void writeAnnotatedForTest(Writer writer, Properties config) throws IOException {
        writeAnnotated(writer, config);
    }

    private static void writeField(Writer writer, Properties config, String key, String comment)
            throws IOException {
        String value = config.getProperty(key);
        if (value == null || value.isBlank()) return;
        writer.write("\n# " + comment + "\n");
        writer.write("# 默认值：" + defaultOf(key) + "\n");
        writer.write(key + "=" + value + "\n");
    }

    private static String defaultOf(String key) {
        for (Field field : SUBAGENT_FIELDS) {
            if (field.key().equals(key)) return field.def();
        }
        return switch (key) {
            case KEY_BASE_URL -> "https://dashscope.aliyuncs.com/compatible-mode";
            case KEY_MODEL -> "qwen3.5-plus-2026-02-15";
            default -> "（无）";
        };
    }

    private static Properties runSetupWizard(Properties existing) {
        Console console = System.console();
        BufferedReader reader = (console != null) ? null : new BufferedReader(new InputStreamReader(System.in));

        System.out.println();
        System.out.println(ANSI_CYAN + "  ╔══════════════════════════════════════╗" + ANSI_RESET);
        System.out.println(ANSI_CYAN + "  ║        POLAR - 初始化设置                ║" + ANSI_RESET);
        System.out.println(ANSI_CYAN + "  ╚══════════════════════════════════════╝" + ANSI_RESET);
        System.out.println();
        System.out.println(ANSI_DIM + "  Configure your LLM API connection." + ANSI_RESET);
        System.out.println(ANSI_DIM + "  Config will be saved to: " + CONFIG_FILE + ANSI_RESET);
        System.out.println(ANSI_DIM + "  You can reconfigure later with /config command." + ANSI_RESET);
        System.out.println();

        // API Base URL (required)
        String currentUrl = existing.getProperty(KEY_BASE_URL, "");
        String baseUrl = promptRequired(console, reader,
                "API Base URL",
                currentUrl,
                "e.g., https://dashscope.aliyuncs.com/compatible-mode, https://api.deepseek.com, https://api.openai.com");

        // API Key (required)
        String currentKey = existing.getProperty(KEY_API_KEY, "");
        String maskedKey = maskKey(currentKey);
        String apiKey = promptSecretRequired(console, reader,
                "API Key",
                maskedKey.isEmpty() ? null : maskedKey,
                currentKey,
                "Your API key (input is hidden)");

        // Model
        String currentModel = existing.getProperty(KEY_MODEL, "");
        String model = promptRequired(console, reader,
                "Model name",
                currentModel,
                "e.g., qwen3.5-plus-2026-02-15, deepseek-chat, gpt-4o");

        System.out.println();
        System.out.println(ANSI_CYAN + "  ── 子 Agent（Sub Agent）──" + ANSI_RESET);
        System.out.println(ANSI_DIM + "  子 Agent 用独立会话并行跑独立任务，跑完只回传最终总结。" + ANSI_RESET);
        System.out.println(ANSI_DIM + "  直接回车使用当前值。" + ANSI_RESET);
        System.out.println();

        for (Field field : SUBAGENT_FIELDS) {
            String current = existing.getProperty(field.key(), field.def());
            String value = promptRequired(console, reader, field.label(), current, field.comment());
            existing.setProperty(field.key(), value);
        }

        Properties config = new Properties();
        config.setProperty(KEY_BASE_URL, baseUrl);
        config.setProperty(KEY_API_KEY, apiKey);
        config.setProperty(KEY_MODEL, model);
        // Copy the subagent keys collected above; runSetupWizard receives and
        // mutates `existing`, so the properties are already set there.
        for (Field field : SUBAGENT_FIELDS) {
            config.setProperty(field.key(), existing.getProperty(field.key(), field.def()));
        }

        System.out.println();
        System.out.println(ANSI_GREEN + "  设置成功!" + ANSI_RESET);
        System.out.println();

        return config;
    }

    private static String promptRequired(Console console, BufferedReader reader,
                                          String label, String defaultValue, String hint) {
        String defaultDisplay = (defaultValue != null && !defaultValue.isBlank())
                ? ANSI_DIM + " [" + defaultValue + "]" + ANSI_RESET : "";
        System.out.println(ANSI_DIM + "  " + hint + ANSI_RESET);

        while (true) {
            System.out.print(ANSI_GREEN + "  " + label + defaultDisplay + ": " + ANSI_RESET);
            String input = readLine(console, reader);
            System.out.println();

            if (input != null && !input.isBlank()) {
                return input.trim();
            }
            if (defaultValue != null && !defaultValue.isBlank()) {
                return defaultValue;
            }
            System.out.println(ANSI_YELLOW + "  (required) Please enter a value." + ANSI_RESET);
        }
    }

    private static String promptSecretRequired(Console console, BufferedReader reader,
                                                String label, String maskedDefault,
                                                String rawDefault, String hint) {
        String defaultDisplay = (maskedDefault != null)
                ? ANSI_DIM + " [" + maskedDefault + "]" + ANSI_RESET : "";
        System.out.println(ANSI_DIM + "  " + hint + ANSI_RESET);

        while (true) {
            System.out.print(ANSI_GREEN + "  " + label + defaultDisplay + ": " + ANSI_RESET);

            String input;
            if (console != null) {
                char[] chars = console.readPassword();
                input = (chars != null) ? new String(chars) : "";
            } else {
                input = readLine(null, reader);
            }
            System.out.println();

            if (input != null && !input.isBlank()) {
                // User entered new value
                if (!input.equals(maskedDefault)) {
                    return input.trim();
                }
            }
            // User pressed Enter - use existing if available
            if (rawDefault != null && !rawDefault.isBlank()) {
                return rawDefault;
            }
            System.out.println(ANSI_YELLOW + "  (required) Please enter a value." + ANSI_RESET);
        }
    }

    private static String readLine(Console console, BufferedReader reader) {
        try {
            if (console != null) {
                return console.readLine();
            }
            return reader.readLine();
        } catch (IOException e) {
            return "";
        }
    }

    private static String maskKey(String key) {
        if (key == null || key.length() <= 8) return "";
        return key.substring(0, 4) + "****" + key.substring(key.length() - 4);
    }

    private static void applyToSystemProperties(Properties config) {
        String baseUrl = config.getProperty(KEY_BASE_URL);
        String apiKey = config.getProperty(KEY_API_KEY);
        String model = config.getProperty(KEY_MODEL);

        if (baseUrl != null && !baseUrl.isBlank()) {
            System.setProperty("spring.ai.openai.base-url", baseUrl);
        }
        if (apiKey != null && !apiKey.isBlank()) {
            System.setProperty("spring.ai.openai.api-key", apiKey);
        }
        if (model != null && !model.isBlank()) {
            System.setProperty("spring.ai.openai.chat.options.model", model);
        }
        applySubagentToSystemProperties(config);
    }

    /**
     * Publishes the subagent keys under their {@code dlc.subagent.*} names so
     * Spring binds them on the next start.
     *
     * <p>System properties win over the placeholders in application.yml, which is
     * what lets a value edited in config.properties override the environment
     * variable of the same setting.</p>
     */
    private static void applySubagentToSystemProperties(Properties config) {
        setIfPresent("dlc.subagent.enabled", config, KEY_SUBAGENT_ENABLED);
        setIfPresent("dlc.subagent.max-depth", config, KEY_SUBAGENT_MAX_DEPTH);
        setIfPresent("dlc.subagent.max-concurrent", config, KEY_SUBAGENT_MAX_CONCURRENT);
        setIfPresent("dlc.subagent.timeout-seconds", config, KEY_SUBAGENT_TIMEOUT);
        setIfPresent("dlc.subagent.max-timeout-seconds", config, KEY_SUBAGENT_MAX_TIMEOUT);
    }

    private static void setIfPresent(String propertyName, Properties config, String key) {
        String value = config.getProperty(key);
        if (value != null && !value.isBlank()) {
            System.setProperty(propertyName, value.trim());
        }
    }

    /**
     * Applies the subagent settings to the live bean so {@code /config} takes
     * effect without a restart.
     *
     * <p>Only the subagent block is applied here; the model connection is
     * already handled by {@code CodingAgent.reloadConfig()}, which has to rebuild
     * the client rather than assign fields.</p>
     */
    public static void applyToRuntime(Properties config, DlcProperties properties) {
        DlcProperties.SubagentConfig subagent = properties.getSubagent();
        String enabled = config.getProperty(KEY_SUBAGENT_ENABLED);
        if (enabled != null && !enabled.isBlank()) {
            subagent.setEnabled(parseBoolean(enabled));
        }
        subagent.setMaxDepth(parseInt(config, KEY_SUBAGENT_MAX_DEPTH, subagent.getMaxDepth(), 0));
        subagent.setMaxConcurrent(parseInt(config, KEY_SUBAGENT_MAX_CONCURRENT,
                subagent.getMaxConcurrent(), 1));
        subagent.setTimeoutSeconds(parseInt(config, KEY_SUBAGENT_TIMEOUT,
                subagent.getTimeoutSeconds(), 1));
        subagent.setMaxTimeoutSeconds(parseInt(config, KEY_SUBAGENT_MAX_TIMEOUT,
                subagent.getMaxTimeoutSeconds(), 1));
    }

    private static boolean parseBoolean(String value) {
        String normalized = value.trim().toLowerCase();
        return !(normalized.equals("false") || normalized.equals("no")
                || normalized.equals("0") || normalized.equals("off"));
    }

    private static int parseInt(Properties config, String key, int fallback, int min) {
        String value = config.getProperty(key);
        if (value == null || value.isBlank()) return fallback;
        try {
            return Math.max(min, Integer.parseInt(value.trim()));
        } catch (NumberFormatException e) {
            System.err.println(ANSI_YELLOW + "Warning: " + key + " 不是合法整数，沿用 " + fallback + ANSI_RESET);
            return fallback;
        }
    }
}
