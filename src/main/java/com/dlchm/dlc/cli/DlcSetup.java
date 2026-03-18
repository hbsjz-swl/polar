package com.dlchm.dlc.cli;

import java.io.*;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Properties;

/**
 * 首次启动配置引导，在 Spring 上下文初始化之前运行。
 * 配置持久化到 ~/.dlc/config.properties
 */
public class DlcSetup {

    private static final String ANSI_RESET = "\u001B[0m";
    private static final String ANSI_CYAN = "\u001B[36m";
    private static final String ANSI_DIM = "\u001B[2m";
    private static final String ANSI_GREEN = "\u001B[32m";
    private static final String ANSI_YELLOW = "\u001B[33m";

    private static final Path CONFIG_DIR = Path.of(System.getProperty("user.home"), ".dlc");
    private static final Path CONFIG_FILE = CONFIG_DIR.resolve("config.properties");

    private static final String KEY_BASE_URL = "base-url";
    private static final String KEY_API_KEY = "api-key";
    private static final String KEY_MODEL = "model";

    /**
     * 检查配置是否存在，不存在则引导用户配置。
     * 将配置设置为系统属性，供 Spring Boot 读取。
     */
    public static void ensureConfigured() {
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
     */
    public static void reconfigure() {
        Properties config = loadConfig();
        config = runSetupWizard(config);
        saveConfig(config);
        applyToSystemProperties(config);
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
                config.store(writer, "DLC Configuration - 蒂爱嘉(北京)有限公司");
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

    private static Properties runSetupWizard(Properties existing) {
        Console console = System.console();
        BufferedReader reader = (console != null) ? null : new BufferedReader(new InputStreamReader(System.in));

        System.out.println();
        System.out.println(ANSI_CYAN + "  ╔══════════════════════════════════════╗" + ANSI_RESET);
        System.out.println(ANSI_CYAN + "  ║        DLC - Initial Setup           ║" + ANSI_RESET);
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

        Properties config = new Properties();
        config.setProperty(KEY_BASE_URL, baseUrl);
        config.setProperty(KEY_API_KEY, apiKey);
        config.setProperty(KEY_MODEL, model);

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
    }
}
