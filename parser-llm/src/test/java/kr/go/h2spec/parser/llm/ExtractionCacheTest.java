package kr.go.h2spec.parser.llm;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import kr.go.h2spec.parser.llm.client.LlmRequest;
import kr.go.h2spec.parser.llm.client.LlmResponse;
import kr.go.h2spec.parser.llm.client.LlmResponse.Finish;
import kr.go.h2spec.parser.llm.client.LlmResponse.Usage;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/** 결과 캐시(설계 5.4)의 키, 저장 규칙, 파일, 위치를 본다. */
class ExtractionCacheTest {

    private static final String PROVIDER = "claude";
    private static final String MODEL = "claude-opus-5-5";
    private static final String EFFORT = "medium";
    private static final LlmRequest REQUEST = new LlmRequest("시스템 프롬프트", "[P1] 요청 URL\n", "문서 전체를 뽑아라",
            "{\"type\":\"object\"}", 64_000);
    private static final ExtractionCache.Key KEY = ExtractionCache.key(REQUEST, PROVIDER, MODEL, EFFORT);
    private static final Usage USAGE = new Usage(100, 20, 30, 400, 0);
    private static final LlmResponse COMPLETE =
            new LlmResponse("{ \"serviceName\" : \"대기오염정보\" }", MODEL, USAGE, Finish.COMPLETE);

    @TempDir
    Path tempDir;

    // ---- 키 ----

    @Test
    void 키는_같은_요청과_설정이면_같고_소문자_16진수_64자다() {
        LlmRequest same = new LlmRequest("시스템 프롬프트", "[P1] 요청 URL\n", "문서 전체를 뽑아라", "{\"type\":\"object\"}", 64_000);

        assertEquals(KEY, ExtractionCache.key(same, PROVIDER, MODEL, EFFORT));
        assertTrue(KEY.sha256().matches("[0-9a-f]{64}"), KEY.sha256());
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("oneComponentChanged")
    void 키는_구성_요소_하나만_바뀌어도_달라진다(String component, ExtractionCache.Key changed) {
        assertNotEquals(KEY, changed, component + "가 바뀌었는데 이전 결과를 쓰면 조용히 다른 결과가 나온다");
    }

    static Stream<Arguments> oneComponentChanged() {
        return Stream.of(
                Arguments.of("시스템 프롬프트", key(new LlmRequest("시스템 프롬프트.", REQUEST.document(), REQUEST.instruction(),
                        REQUEST.jsonSchema(), REQUEST.maxOutputTokens()), PROVIDER, MODEL, EFFORT)),
                Arguments.of("문서", key(new LlmRequest(REQUEST.systemPrompt(), "[P1] 요청 주소\n", REQUEST.instruction(),
                        REQUEST.jsonSchema(), REQUEST.maxOutputTokens()), PROVIDER, MODEL, EFFORT)),
                // 오퍼레이션별 호출(2-2)은 문서가 같고 지시만 다르다
                Arguments.of("작업 지시", key(new LlmRequest(REQUEST.systemPrompt(), REQUEST.document(), "getMsrstnList만 뽑아라",
                        REQUEST.jsonSchema(), REQUEST.maxOutputTokens()), PROVIDER, MODEL, EFFORT)),
                Arguments.of("스키마", key(new LlmRequest(REQUEST.systemPrompt(), REQUEST.document(), REQUEST.instruction(),
                        "{\"type\":\"array\"}", REQUEST.maxOutputTokens()), PROVIDER, MODEL, EFFORT)),
                Arguments.of("회사", key(REQUEST, "openai", MODEL, EFFORT)),
                Arguments.of("모델", key(REQUEST, PROVIDER, "claude-sonnet-5-5", EFFORT)),
                Arguments.of("추론 깊이", key(REQUEST, PROVIDER, MODEL, "high")),
                Arguments.of("출력 한도", key(new LlmRequest(REQUEST.systemPrompt(), REQUEST.document(), REQUEST.instruction(),
                        REQUEST.jsonSchema(), 128_000), PROVIDER, MODEL, EFFORT)));
    }

    @Test
    void 값의_경계를_옮겨_이으면_같아지는_요청도_키가_다르다() {
        LlmRequest documentLonger = new LlmRequest("프롬프트", "문서A", "지시", "{}", 1);
        LlmRequest instructionLonger = new LlmRequest("프롬프트", "문서", "A지시", "{}", 1);

        assertNotEquals(key(documentLonger, PROVIDER, MODEL, EFFORT), key(instructionLonger, PROVIDER, MODEL, EFFORT));
        assertNotEquals(key(REQUEST, "claude", "x-model", EFFORT), key(REQUEST, "claudex", "-model", EFFORT));
    }

    @Test
    void 키는_소문자_16진수_64자만_받는다() {
        // 파일 이름이 되므로 경로 구분자가 든 키는 캐시 디렉터리 밖을 가리킬 수 있다
        assertThrows(IllegalArgumentException.class, () -> new ExtractionCache.Key("../../.ssh/id_rsa"));
        assertThrows(IllegalArgumentException.class, () -> new ExtractionCache.Key("A".repeat(64)));
        assertThrows(IllegalArgumentException.class, () -> new ExtractionCache.Key(null));
    }

    // ---- 저장 규칙 ----

    @Test
    void 완료된_응답은_JSON_원본과_사용량을_저장했다가_그대로_돌려준다() throws IOException {
        Path directory = tempDir.resolve("llm");
        ExtractionCache cache = ExtractionCache.at(directory);

        cache.store(KEY, COMPLETE);

        assertEquals(Optional.of(COMPLETE), cache.find(KEY));
        JsonNode file = readJson(directory.resolve(KEY.sha256() + ".json"));
        assertEquals("{ \"serviceName\" : \"대기오염정보\" }", file.get("json").asText(), "검증·정규화 전의 원본 글 그대로");
        assertEquals("COMPLETE", file.get("finish").asText());
        assertEquals(400, file.get("usage").get("outputTokens").asLong());
    }

    @Test
    void 잘린_응답은_결과_대신_잘림_표시만_저장한다() throws IOException {
        Path directory = tempDir.resolve("llm");
        ExtractionCache cache = ExtractionCache.at(directory);

        cache.store(KEY, new LlmResponse("{\"serviceName\": \"대기", MODEL, USAGE, Finish.TRUNCATED));

        assertEquals(Optional.of(new LlmResponse("", MODEL, USAGE, Finish.TRUNCATED)), cache.find(KEY));
        JsonNode file = readJson(directory.resolve(KEY.sha256() + ".json"));
        assertEquals("TRUNCATED", file.get("finish").asText());
        assertFalse(file.has("json"), "잘린 출력은 결과로 쓸 수 없어 남기지 않는다");
    }

    @Test
    void 거절은_저장하지_않는다() throws IOException {
        Path directory = tempDir.resolve("llm");
        ExtractionCache cache = ExtractionCache.at(directory);

        cache.store(KEY, new LlmResponse("", MODEL, USAGE, Finish.REFUSED));

        assertEquals(Optional.empty(), cache.find(KEY));
        assertFalse(Files.exists(directory));
    }

    @Test
    void 꺼진_캐시는_저장하지도_찾지도_않는다() throws IOException {
        ExtractionCache cache = ExtractionCache.disabled();

        cache.store(KEY, COMPLETE);

        assertEquals(Optional.empty(), cache.find(KEY));
        assertFalse(cache.enabled());
        assertEquals(Optional.empty(), cache.directory());
    }

    @Test
    void 같은_키에_다시_저장하면_바꾸고_임시_파일을_남기지_않는다() throws IOException {
        Path directory = tempDir.resolve("llm");
        ExtractionCache cache = ExtractionCache.at(directory);
        LlmResponse second = new LlmResponse("{\"serviceName\":\"다시 받은 결과\"}", MODEL, USAGE, Finish.COMPLETE);

        cache.store(KEY, new LlmResponse("", MODEL, USAGE, Finish.TRUNCATED));
        cache.store(KEY, second);

        assertEquals(Optional.of(second), cache.find(KEY));
        try (Stream<Path> files = Files.list(directory)) {
            assertEquals(List.of(KEY.sha256() + ".json"), files.map(file -> file.getFileName().toString()).toList());
        }
    }

    @Test
    void 처음_저장할_때_디렉터리를_만들고_POSIX에서는_소유자만_읽고_쓰게_한다() throws IOException {
        assumeTrue(tempDir.getFileSystem().supportedFileAttributeViews().contains("posix"), "POSIX 권한을 모르는 파일 시스템");
        Path directory = tempDir.resolve("cache").resolve("h2spec").resolve("llm");

        ExtractionCache.at(directory).store(KEY, COMPLETE);

        assertEquals(PosixFilePermissions.fromString("rwx------"), Files.getPosixFilePermissions(directory));
        assertEquals(PosixFilePermissions.fromString("rw-------"),
                Files.getPosixFilePermissions(directory.resolve(KEY.sha256() + ".json")));
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "",
            "{",
            "[]",
            "{\"format\": 2, \"finish\": \"COMPLETE\", \"model\": \"m\", \"json\": \"{}\", \"usage\": {}}",
            "{\"format\": 1, \"finish\": \"REFUSED\", \"model\": \"m\", \"usage\": {\"inputTokens\": 1, \"cacheReadInputTokens\": 0,"
                    + " \"cacheWriteInputTokens\": 0, \"outputTokens\": 1, \"reasoningTokens\": 0}}",
            "{\"format\": 1, \"finish\": \"COMPLETE\", \"model\": \"m\", \"usage\": {\"inputTokens\": 1, \"cacheReadInputTokens\": 0,"
                    + " \"cacheWriteInputTokens\": 0, \"outputTokens\": 1, \"reasoningTokens\": 0}}",
            "{\"format\": 1, \"finish\": \"COMPLETE\", \"model\": \"m\", \"json\": \"{}\", \"usage\": {\"inputTokens\": \"많음\"}}"})
    void 읽을_수_없는_캐시_파일은_없는_것으로_본다(String content) throws IOException {
        Path directory = Files.createDirectories(tempDir.resolve("llm"));
        Files.writeString(directory.resolve(KEY.sha256() + ".json"), content);

        assertEquals(Optional.empty(), ExtractionCache.at(directory).find(KEY), "다음에 받은 응답이 덮어쓴다");
    }

    // ---- 위치 ----

    @Test
    void 기본_위치는_XDG_CACHE_HOME_아래이고_없거나_상대_경로면_홈의_cache_아래다() {
        String home = tempDir.resolve("home").toString();
        String cacheHome = tempDir.resolve("xdg").toString();

        assertEquals(Path.of(cacheHome, "h2spec", "llm"),
                ExtractionCache.defaultDirectory(Map.of("XDG_CACHE_HOME", cacheHome), "Linux", home));
        assertEquals(Path.of(home, ".cache", "h2spec", "llm"), ExtractionCache.defaultDirectory(Map.of(), "Linux", home));
        assertEquals(Path.of(home, ".cache", "h2spec", "llm"),
                ExtractionCache.defaultDirectory(Map.of("XDG_CACHE_HOME", " "), "Mac OS X", home));
        assertEquals(Path.of(home, ".cache", "h2spec", "llm"),
                ExtractionCache.defaultDirectory(Map.of("XDG_CACHE_HOME", "relative/cache"), "Linux", home),
                "XDG 명세대로 절대 경로가 아닌 값은 무시한다");
    }

    @Test
    void 윈도에서는_LOCALAPPDATA_아래이고_없으면_그_기본_위치다() {
        String localAppData = "C:\\Users\\me\\AppData\\Local";

        assertEquals(Path.of(localAppData, "h2spec", "llm"), ExtractionCache.defaultDirectory(
                Map.of("LOCALAPPDATA", localAppData, "XDG_CACHE_HOME", tempDir.toString()), "Windows 11", "C:\\Users\\me"));
        assertEquals(Path.of("C:\\Users\\me", "AppData", "Local", "h2spec", "llm"),
                ExtractionCache.defaultDirectory(Map.of(), "Windows 11", "C:\\Users\\me"));
    }

    private static ExtractionCache.Key key(LlmRequest request, String provider, String model, String effort) {
        return ExtractionCache.key(request, provider, model, effort);
    }

    private static JsonNode readJson(Path file) throws IOException {
        return new ObjectMapper().readTree(file.toFile());
    }
}
