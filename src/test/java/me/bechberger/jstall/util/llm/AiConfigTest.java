package me.bechberger.jstall.util.llm;

import org.junit.jupiter.api.Test;

import java.util.Properties;

import static org.assertj.core.api.Assertions.assertThat;

class AiConfigTest {

    @Test
    void testLocalProviderFromProperties() {
        Properties p = new Properties();
        p.setProperty("provider", "local");
        p.setProperty("model", "my-model");
        p.setProperty("local.host", "http://localhost:9090");

        AiConfig cfg = AiConfig.fromProperties(p);
        assertThat(cfg.provider()).isEqualTo(AiConfig.Provider.LOCAL);
        assertThat(cfg.model()).isEqualTo("my-model");
        assertThat(cfg.localHost()).isEqualTo("http://localhost:9090");
        assertThat(cfg.isLocal()).isTrue();
    }

    @Test
    void testGardenerProviderFromProperties() {
        Properties p = new Properties();
        p.setProperty("provider", "gardener");
        p.setProperty("model", "gpt-50-nano");
        p.setProperty("api.key", "test-key");

        AiConfig cfg = AiConfig.fromProperties(p);
        assertThat(cfg.provider()).isEqualTo(AiConfig.Provider.GARDENER);
        assertThat(cfg.model()).isEqualTo("gpt-50-nano");
        assertThat(cfg.apiKey()).isEqualTo("test-key");
        assertThat(cfg.isLocal()).isFalse();
    }

    @Test
    void testDefaultProviderIsGardener() {
        Properties p = new Properties();

        AiConfig cfg = AiConfig.fromProperties(p);
        assertThat(cfg.provider()).isEqualTo(AiConfig.Provider.GARDENER);
        assertThat(cfg.model()).isEqualTo("gpt-50-nano");
    }

    @Test
    void testDefaultModelForLocal() {
        Properties p = new Properties();
        p.setProperty("provider", "local");

        AiConfig cfg = AiConfig.fromProperties(p);
        assertThat(cfg.model()).isEqualTo("local");
    }

    @Test
    void testDefaultLocalHost() {
        Properties p = new Properties();
        p.setProperty("provider", "local");

        AiConfig cfg = AiConfig.fromProperties(p);
        assertThat(cfg.localHost()).isEqualTo("http://127.0.0.1:8080");
    }

    @Test
    void testLlamaServerModelProperty() {
        Properties p = new Properties();
        p.setProperty("provider", "local");
        p.setProperty("local.llama-server-model", "AaryanK/Qwen3.5-9B-GGUF:Q8_0");

        AiConfig cfg = AiConfig.fromProperties(p);
        assertThat(cfg.llamaServerModel()).isEqualTo("AaryanK/Qwen3.5-9B-GGUF:Q8_0");
    }

    @Test
    void testLlamaServerModelPropertyLegacy() {
        Properties p = new Properties();
        p.setProperty("provider", "local");
        p.setProperty("llama-server.model", "AaryanK/Qwen3.5-9B-GGUF:Q8_0");

        AiConfig cfg = AiConfig.fromProperties(p);
        assertThat(cfg.llamaServerModel()).isEqualTo("AaryanK/Qwen3.5-9B-GGUF:Q8_0");
    }

    @Test
    void testNoLlamaServerModelByDefault() {
        Properties p = new Properties();
        p.setProperty("provider", "local");

        AiConfig cfg = AiConfig.fromProperties(p);
        assertThat(cfg.llamaServerModel()).isNull();
    }

    // Backward compatibility: legacy provider names map to LOCAL

    @Test
    void testOllamaProviderMapsToLocal() {
        Properties p = new Properties();
        p.setProperty("provider", "ollama");

        AiConfig cfg = AiConfig.fromProperties(p);
        assertThat(cfg.provider()).isEqualTo(AiConfig.Provider.LOCAL);
        assertThat(cfg.isLocal()).isTrue();
    }

    @Test
    void testLlamaServerProviderMapsToLocal() {
        Properties p = new Properties();
        p.setProperty("provider", "llama-server");

        AiConfig cfg = AiConfig.fromProperties(p);
        assertThat(cfg.provider()).isEqualTo(AiConfig.Provider.LOCAL);
    }

    @Test
    void testLlamaServerUnderscoreProviderMapsToLocal() {
        Properties p = new Properties();
        p.setProperty("provider", "llama_server");

        AiConfig cfg = AiConfig.fromProperties(p);
        assertThat(cfg.provider()).isEqualTo(AiConfig.Provider.LOCAL);
    }

    // Backward compatibility: legacy host property names

    @Test
    void testLegacyOllamaHostFallback() {
        Properties p = new Properties();
        p.setProperty("provider", "local");
        p.setProperty("ollama.host", "http://localhost:11434");

        AiConfig cfg = AiConfig.fromProperties(p);
        assertThat(cfg.localHost()).isEqualTo("http://localhost:11434");
    }

    @Test
    void testLegacyLlamaServerHostFallback() {
        Properties p = new Properties();
        p.setProperty("provider", "local");
        p.setProperty("llama-server.host", "http://localhost:9999");

        AiConfig cfg = AiConfig.fromProperties(p);
        assertThat(cfg.localHost()).isEqualTo("http://localhost:9999");
    }

    @Test
    void testLocalHostTakesPrecedenceOverLegacy() {
        Properties p = new Properties();
        p.setProperty("provider", "local");
        p.setProperty("local.host", "http://localhost:8080");
        p.setProperty("ollama.host", "http://localhost:11434");
        p.setProperty("llama-server.host", "http://localhost:9999");

        AiConfig cfg = AiConfig.fromProperties(p);
        assertThat(cfg.localHost()).isEqualTo("http://localhost:8080");
    }
}
