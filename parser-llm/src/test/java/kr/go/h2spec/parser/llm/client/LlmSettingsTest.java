package kr.go.h2spec.parser.llm.client;

import org.junit.jupiter.api.Test;

import java.net.URI;
import java.time.Duration;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

class LlmSettingsTest {

    private static final String API_KEY = "sk-ant-api03-SECRET_key-0123";

    @Test
    void 기본값은_10분_대기와_재시도_3번과_회사_기본_주소다() {
        LlmSettings settings = LlmSettings.of("claude-opus-5-5", "medium", API_KEY);

        assertEquals(Duration.ofMinutes(10), settings.requestTimeout());
        assertEquals(3, settings.retryAttempts());
        assertNull(settings.baseUrl());
    }

    @Test
    void with는_한_값만_바꾼다() {
        LlmSettings settings = LlmSettings.of("claude-opus-5-5", "medium", API_KEY)
                .withRequestTimeout(Duration.ofSeconds(30))
                .withRetryAttempts(0)
                .withBaseUrl(URI.create("http://127.0.0.1:8080"));

        assertEquals(new LlmSettings("claude-opus-5-5", "medium", API_KEY, Duration.ofSeconds(30), 0,
                URI.create("http://127.0.0.1:8080")), settings);
        assertNull(settings.withBaseUrl(null).baseUrl());
    }

    @Test
    void toString은_키를_가린다() {
        String text = LlmSettings.of("claude-opus-5-5", "medium", API_KEY).toString();

        assertEquals("LlmSettings[model=claude-opus-5-5, effort=medium, apiKey=****, requestTimeout=PT10M, "
                + "retryAttempts=3, baseUrl=null]", text);
        assertFalse(text.contains("SECRET"));
    }

    @Test
    void 요청_머리에_실을_수_없는_키는_키_값_없이_알린다() {
        // 그대로 두면 JDK HttpClient가 머리 값을 담은 예외 메시지를 낸다
        for (String key : List.of("", API_KEY + "\n", "sk-ant SECRET", "sk-ant-SECRET키", "\tsk-ant-SECRET")) {
            IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                    () -> LlmSettings.of("claude-opus-5-5", "medium", key), key);

            assertEquals("API 키가 비었거나 공백·제어 문자·ASCII 밖의 글자가 들어 있습니다", e.getMessage());
        }
    }

    @Test
    void 잘못된_값은_만들_때_알린다() {
        LlmSettings settings = LlmSettings.of("claude-opus-5-5", "medium", API_KEY);

        assertThrows(IllegalArgumentException.class, () -> LlmSettings.of(" ", "medium", API_KEY));
        assertThrows(IllegalArgumentException.class, () -> LlmSettings.of("claude-opus-5-5", null, API_KEY));
        assertThrows(NullPointerException.class, () -> LlmSettings.of("claude-opus-5-5", "medium", null));
        assertThrows(IllegalArgumentException.class, () -> settings.withRequestTimeout(Duration.ZERO));
        assertThrows(IllegalArgumentException.class, () -> settings.withRetryAttempts(-1));
        for (String url : List.of("ftp://127.0.0.1", "127.0.0.1:8080", "http://127.0.0.1/?a=b", "file:///tmp")) {
            assertThrows(IllegalArgumentException.class, () -> settings.withBaseUrl(URI.create(url)), url);
        }
    }
}
