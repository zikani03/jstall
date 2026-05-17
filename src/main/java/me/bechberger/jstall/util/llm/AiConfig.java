package me.bechberger.jstall.util.llm;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Properties;

/**
 * Configuration for AI providers (Gardener or local OpenAI-compatible server).
 * <p>
 * Reads from .jstall-ai-config file in current directory or home directory.
 * Falls back to .gaw file for backward compatibility with Gardener API key.
 * <p>
 * Config file format (Java properties):
 * <pre>
 * # Local OpenAI-compatible server (llama-server, vLLM, etc.):
 * provider=local
 * local.host=http://127.0.0.1:8080
 *
 * # Auto-launch llama-server with a HuggingFace model:
 * local.llama-server-model=AaryanK/Qwen3.5-9B-GGUF:Q8_0
 * # Smaller alternative:
 * #local.llama-server-model=AaryanK/Qwen3.5-4B-GGUF:Q8_0
 *
 * # Remote Gardener provider:
 * provider=gardener
 * model=gpt-50-nano
 * api.key=your-gardener-api-key
 * </pre>
 */
public record AiConfig(Provider provider, String model, String apiKey, String localHost,
                       String llamaServerModel) {

    private static final String CONFIG_FILENAME = ".jstall-ai-config";
    private static final String GAW_FILENAME = ".gaw";

    public enum Provider {
        GARDENER,
        LOCAL
    }

    /**
     * Creates a config with explicit values (no llama-server model).
     */
    public AiConfig(Provider provider, String model, String apiKey, String localHost) {
        this(provider, model, apiKey, localHost, null);
    }

    /**
     * Creates a config with explicit values.
     */
    public AiConfig {
    }

    /**
     * Loads configuration from file system.
     *
     * @return Loaded configuration
     * @throws ConfigNotFoundException if no configuration is found
     */
    public static AiConfig load() throws ConfigNotFoundException {
        // Try to load from .jstall-ai-config
        Properties props = loadConfigFile();

        if (props != null) {
            return fromProperties(props);
        }

        // Fall back to .gaw file for Gardener API key
        String gawKey = loadGawFile();
        if (gawKey != null) {
            return new AiConfig(
                    Provider.GARDENER,
                    "gpt-50-nano",
                    gawKey,
                    null
            );
        }

        // Fall back to environment variable (backward compatibility)
        String envKey = System.getenv("ANSWERING_MACHINE_APIKEY");
        if (envKey != null && !envKey.trim().isEmpty()) {
            return new AiConfig(
                    Provider.GARDENER,
                    "gpt-50-nano",
                    envKey.trim(),
                    null
            );
        }

        throw new ConfigNotFoundException(
                "AI configuration not found. Please create a " + CONFIG_FILENAME + " file in the current directory or home directory, " +
                "or create a .gaw file with your Gardener API key, " +
                "or set the ANSWERING_MACHINE_APIKEY environment variable."
        );
    }

    private static Properties loadConfigFile() {
        // Try current directory
        String currentDir = System.getProperty("user.dir");
        if (currentDir != null) {
            Path configPath = Paths.get(currentDir, CONFIG_FILENAME);
            Properties props = tryLoadProperties(configPath);
            if (props != null) {
                return props;
            }
        }

        // Try home directory
        String home = System.getProperty("user.home");
        if (home != null) {
            Path configPath = Paths.get(home, CONFIG_FILENAME);
            Properties props = tryLoadProperties(configPath);
            return props;
        }

        return null;
    }

    private static Properties tryLoadProperties(Path path) {
        if (Files.exists(path)) {
            try {
                Properties props = new Properties();
                props.load(Files.newBufferedReader(path));
                return props;
            } catch (IOException e) {
                // Continue to next source
            }
        }
        return null;
    }

    private static String loadGawFile() {
        // Try current directory
        String currentDir = System.getProperty("user.dir");
        if (currentDir != null) {
            Path gawPath = Paths.get(currentDir, GAW_FILENAME);
            String key = tryReadFile(gawPath);
            if (key != null) {
                return key;
            }
        }

        // Try home directory
        String home = System.getProperty("user.home");
        if (home != null) {
            Path gawPath = Paths.get(home, GAW_FILENAME);
            String key = tryReadFile(gawPath);
            return key;
        }

        return null;
    }

    private static String tryReadFile(Path path) {
        if (Files.exists(path)) {
            try {
                String content = Files.readString(path).trim();
                if (!content.isEmpty()) {
                    return content;
                }
            } catch (IOException e) {
                // Continue
            }
        }
        return null;
    }

    static AiConfig fromProperties(Properties props) {
        String providerStr = props.getProperty("provider", "gardener").toLowerCase();
        Provider provider = switch (providerStr) {
            case "local", "ollama", "llama-server", "llamaserver", "llama_server" -> Provider.LOCAL;
            default -> Provider.GARDENER;
        };

        String model = props.getProperty("model",
                provider == Provider.LOCAL ? "local" : "gpt-50-nano");

        String apiKey = props.getProperty("api.key");

        // Accept both "local.host" and legacy "ollama.host" / "llama-server.host"
        String localHost = props.getProperty("local.host");
        if (localHost == null) {
            localHost = props.getProperty("llama-server.host");
        }
        if (localHost == null) {
            localHost = props.getProperty("ollama.host");
        }
        if (localHost == null) {
            localHost = "http://127.0.0.1:8080";
        }

        // HuggingFace model for auto-launching llama-server
        String llamaServerModel = props.getProperty("local.llama-server-model");
        if (llamaServerModel == null) {
            llamaServerModel = props.getProperty("llama-server.model");
        }

        return new AiConfig(provider, model, apiKey, localHost, llamaServerModel);
    }

    public boolean isLocal() {
        return provider == Provider.LOCAL;
    }

    /**
     * Exception thrown when configuration cannot be found.
     */
    public static class ConfigNotFoundException extends Exception {
        public ConfigNotFoundException(String message) {
            super(message);
        }
    }
}
