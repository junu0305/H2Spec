package kr.go.h2spec.parser.llm;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import kr.go.h2spec.parser.Block;
import kr.go.h2spec.parser.RuleEngine;
import kr.go.h2spec.parser.SpecParseException;
import kr.go.h2spec.parser.SpecParseResult;
import kr.go.h2spec.parser.SpecParseResult.Engine;
import kr.go.h2spec.parser.SpecParseResult.TokenUsage;
import kr.go.h2spec.parser.llm.client.LlmClient;
import kr.go.h2spec.parser.llm.client.LlmException;
import kr.go.h2spec.parser.llm.client.LlmProviderFactory;
import kr.go.h2spec.parser.llm.client.LlmRequest;
import kr.go.h2spec.parser.llm.client.LlmResponse;
import kr.go.h2spec.parser.llm.client.LlmSettings;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.MethodSource;

import java.io.IOException;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.function.Consumer;
import java.util.stream.Stream;

import static kr.go.h2spec.parser.llm.SampleDocument.standardOperation;
import static kr.go.h2spec.parser.llm.ScriptedLlmClient.complete;
import static kr.go.h2spec.parser.llm.ScriptedLlmClient.refused;
import static kr.go.h2spec.parser.llm.ScriptedLlmClient.truncated;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * llm 엔진의 조립(1-7)을 본다. 문서는 {@link SampleDocument}의 작은 표준 기술문서이고, 회사 대신 정해 둔 응답을 돌려주는
 * {@link ScriptedLlmClient}를 쓴다. 실제 문서와 녹화로 끝까지 돌리는 것은 {@link RecordedFlowTest}가 본다.
 */
class LlmSpecParserTest {

    private static final String PROVIDER = "claude";
    private static final String MODEL = "claude-opus-5-5";
    private static final String EFFORT = "medium";
    private static final String FILE = "sample.docx";
    private static final List<Block> BLOCKS = SampleDocument.standard();
    private static final ObjectMapper MAPPER = new ObjectMapper();
    /** {@link ScriptedLlmClient#USAGE}를 엔진 정보의 꼴로 옮긴 것 */
    private static final TokenUsage ONE_CALL = new TokenUsage(1_000, 200, 300, 4_000);

    @TempDir
    Path tempDir;

    private final List<String> warnings = new ArrayList<>();

    // ---- 요청 ----

    @Test
    void 문서_하나에_시스템_프롬프트와_직렬화한_문서와_작업_지시와_스키마를_한_번_보낸다() throws Exception {
        ScriptedLlmClient client = new ScriptedLlmClient().reply(complete(json(standardOperation())));

        parser(client, options()).extract(BLOCKS, FILE, "DOCX");

        ExtractionPrompt prompt = ExtractionPrompt.load();
        assertEquals(1, client.calls());
        LlmRequest request = client.requests().get(0);
        assertEquals(prompt.promptText(), request.systemPrompt());
        assertEquals(new DocumentSerializer().serialize(BLOCKS), request.document(), "<document> 태그는 어댑터가 감싼다");
        assertEquals(prompt.wholeDocumentInstruction(), request.instruction());
        assertTrue(request.instruction().contains("문서 전체"), request.instruction());
        assertEquals(prompt.schemaText(), request.jsonSchema());
        assertEquals(LlmSpecParser.DEFAULT_MAX_OUTPUT_TOKENS, request.maxOutputTokens());
        assertEquals(64_000, LlmSpecParser.DEFAULT_MAX_OUTPUT_TOKENS);
    }

    @Test
    void 출력_한도는_설정으로_바꾼다() throws Exception {
        ScriptedLlmClient client = new ScriptedLlmClient().reply(complete(json(standardOperation())));

        parser(client, options().withMaxOutputTokens(8_000)).extract(BLOCKS, FILE, "DOCX");

        assertEquals(8_000, client.requests().get(0).maxOutputTokens());
        assertThrows(IllegalArgumentException.class, () -> options().withMaxOutputTokens(0));
    }

    // ---- 결과 ----

    @Test
    void 변환한_오퍼레이션을_IR과_함께_돌려주고_엔진_정보에_회사_모델_추론_깊이와_토큰_사용량을_담는다() throws Exception {
        ScriptedLlmClient client = new ScriptedLlmClient().reply(complete(json(standardOperation())));

        SpecParseResult result = parser(client, options()).extract(BLOCKS, FILE, "DOCX");

        assertEquals(new Engine("llm", PROVIDER, MODEL, EFFORT, false, ONE_CALL), result.engine());
        assertEquals(1, result.apis().size());
        assertEquals(List.of(), result.skipped());
        SpecParseResult.Operation operation = result.operations().get(0);
        assertEquals("getMsrstnList", operation.operationName());
        assertEquals("측정소 목록 조회", operation.title());
        assertNull(operation.skipReason());
        assertFalse(operation.outOfScope());
        assertEquals("MsrstnList", operation.api().apiId());
        JsonNode metadata = operation.api().ir().get("metadata");
        assertEquals(FILE, metadata.get("sourceFile").asText());
        assertEquals("llm", metadata.get("engine").asText());
        assertEquals(PROVIDER, metadata.get("llm").get("provider").asText());
        assertEquals(MODEL, metadata.get("llm").get("model").asText());
        assertEquals(EFFORT, metadata.get("llm").get("effort").asText());
        assertFalse(metadata.toString().toLowerCase().contains("token"), "토큰 수는 실행마다 달라 IR에 넣지 않는다");
    }

    @Test
    void 검증에서_건너뛴_오퍼레이션은_오퍼레이션명_기능명_순번_순으로_이름을_붙여_건너뜀_목록에_남긴다() throws Exception {
        ScriptedLlmClient client = new ScriptedLlmClient().reply(complete(json(
                standardOperation(),
                // 앞 오퍼레이션과 주소가 같아 V11이 건너뛴다. 이름도 기능명도 없어 순번으로 부른다
                standardOperation().name("").title(""),
                // 응답 필드가 하나도 없어 V10이 건너뛴다
                standardOperation().name("").title("필드 없는 조회").fields())));

        SpecParseResult result = parser(client, options()).extract(BLOCKS, FILE, "DOCX");

        assertEquals(List.of(
                "오퍼레이션 2: 주소가 앞 오퍼레이션(getMsrstnList)과 같습니다: "
                        + "http://apis.data.go.kr/B552584/MsrstnInfoInqireSvc/getMsrstnList",
                "필드 없는 조회: 검증을 통과한 응답 필드가 없습니다"), result.skipped());
        assertEquals(3, result.operations().size(), "건너뛴 오퍼레이션도 교차검증과 평가가 짝지을 수 있게 기록을 남긴다");
        assertNull(result.operations().get(1).api());
        assertNull(result.operations().get(1).title());
        assertEquals("필드 없는 조회", result.operations().get(2).title());
        assertEquals(1, result.apis().size());
    }

    @Test
    void LLM이_오퍼레이션을_하나도_찾지_못하면_rule_엔진과_같은_문구로_실패를_알린다() throws Exception {
        Path document = TestDocuments.path("its-traffic-light.hwp");
        ScriptedLlmClient client = new ScriptedLlmClient().reply(complete(json()), complete(json()));

        SpecParseException e = assertThrows(SpecParseException.class, () -> parser(client, options()).parse(document));

        assertEquals(SpecParseException.Kind.FAILED, e.kind());
        assertEquals("문서에서 상세기능 명세를 찾지 못했습니다: its-traffic-light.hwp", e.getMessage());
        assertEquals(Optional.of(new Engine("llm", PROVIDER, MODEL, EFFORT, false, ONE_CALL)), e.engine(),
                "LLM은 이미 답해 과금됐으므로 CLI가 토큰을 알릴 수 있게 담는다");
        // 평가 도구가 쓰는 extract는 던지지 않고 빈 결과를 돌려준다
        assertEquals(List.of(), parser(client, options()).extract(document).operations());
    }

    @Test
    void 명세_문서가_아니면_부르지_않고_IllegalArgumentException을_던진다() throws IOException {
        Path ir = Files.writeString(tempDir.resolve("MsrstnList.json"), "{}");
        ScriptedLlmClient client = new ScriptedLlmClient();

        IllegalArgumentException e = assertThrows(IllegalArgumentException.class, () -> parser(client, options()).parse(ir));

        assertEquals("명세 문서(.docx, .hwp, .hwpx)가 아닙니다: MsrstnList.json", e.getMessage());
        assertEquals(0, client.calls());
    }

    // ---- 실패 처리 (설계 7.3) ----

    @ParameterizedTest
    @CsvSource({"AUTH, AUTH", "RATE_LIMITED, RETRYABLE", "UNAVAILABLE, RETRYABLE", "BAD_REQUEST, FAILED"})
    void 호출_실패를_CLI가_가르는_실패_종류로_바꾸고_다시_부르지_않는다(LlmException.Kind kind, SpecParseException.Kind expected) {
        LlmException failure = new LlmException(kind, "Claude API 오류 (HTTP 4xx): 테스트 오류");
        ScriptedLlmClient client = new ScriptedLlmClient().fail(failure);

        SpecParseException e = assertThrows(SpecParseException.class,
                () -> parser(client, options()).extract(BLOCKS, FILE, "DOCX"));

        assertEquals(expected, e.kind());
        assertTrue(e.getMessage().contains("(" + FILE + ", " + PROVIDER + "): Claude API 오류 (HTTP 4xx): 테스트 오류"),
                e.getMessage());
        assertSame(failure, e.getCause());
        assertEquals(1, client.calls(), "다시 부르면 나을 실패는 어댑터가 이미 재시도했다");
        assertEquals(Optional.empty(), e.engine(), "답을 받은 호출이 없으면 알릴 토큰이 없다");
    }

    @Test
    void 실패_메시지는_무엇이_잘못됐는지와_문서와_회사와_어댑터의_설명을_담는다() {
        ScriptedLlmClient client = new ScriptedLlmClient()
                .fail(new LlmException(LlmException.Kind.AUTH, "Claude API 오류 (HTTP 401): authentication_error: invalid x-api-key"))
                .fail(new LlmException(LlmException.Kind.RATE_LIMITED, "Claude API 오류 (HTTP 429) (4번 시도)"));

        assertEquals("LLM API 키 오류로 변환하지 못했습니다 (sample.docx, claude): "
                + "Claude API 오류 (HTTP 401): authentication_error: invalid x-api-key", failure(client).getMessage());
        assertEquals("LLM 호출 한도에 걸려 변환하지 못했습니다. 잠시 뒤 다시 실행하세요 (sample.docx, claude): "
                + "Claude API 오류 (HTTP 429) (4번 시도)", failure(client).getMessage());
    }

    @Test
    void 출력이_잘리면_그_문서를_실패로_알리고_잘림_표시를_남겨_같은_요청은_다시_부르지_않는다() throws Exception {
        ExtractionCache cache = ExtractionCache.at(tempDir.resolve("cache"));
        ScriptedLlmClient client = new ScriptedLlmClient().reply(truncated(64_000));

        SpecParseException e = assertThrows(SpecParseException.class,
                () -> parser(client, options().withCache(cache)).extract(BLOCKS, FILE, "DOCX"));

        assertEquals(SpecParseException.Kind.FAILED, e.kind());
        assertEquals("LLM 출력이 출력 한도 64000 토큰에 걸려 잘렸습니다 (sample.docx, 출력 64000 토큰, thinking 포함). "
                + "나눠 부르기가 들어오기 전에는 출력이 잘리는 문서를 llm 엔진으로 변환하지 못합니다", e.getMessage());
        assertEquals(Optional.of(new Engine("llm", PROVIDER, MODEL, EFFORT, false, new TokenUsage(1_000, 0, 0, 64_000))),
                e.engine(), "잘린 출력도 과금된다");

        SpecParseException again = assertThrows(SpecParseException.class,
                () -> parser(client, options().withCache(cache)).extract(BLOCKS, FILE, "DOCX"));
        assertEquals(1, client.calls(), "잘림 표시가 있으면 다시 부르지(과금하지) 않는다");
        assertEquals(SpecParseException.Kind.FAILED, again.kind());
        assertTrue(again.getMessage().contains("결과 캐시에 잘림 표시가 있어 다시 부르지 않음"), again.getMessage());
        assertEquals(Optional.of(new Engine("llm", PROVIDER, MODEL, EFFORT, true, TokenUsage.NONE)), again.engine());

        // 출력 한도를 바꾸면 키가 달라져 다시 부른다
        client.reply(complete(json(standardOperation())));
        SpecParseResult result = parser(client, options().withCache(cache).withMaxOutputTokens(128_000))
                .extract(BLOCKS, FILE, "DOCX");
        assertEquals(2, client.calls());
        assertEquals(1, result.apis().size());
    }

    @Test
    void 거절은_그_문서를_실패로_알리고_저장하지_않아_다음_실행은_다시_부른다() throws Exception {
        ExtractionCache cache = ExtractionCache.at(tempDir.resolve("cache"));
        ScriptedLlmClient client = new ScriptedLlmClient().reply(refused(), complete(json(standardOperation())));

        SpecParseException e = assertThrows(SpecParseException.class,
                () -> parser(client, options().withCache(cache)).extract(BLOCKS, FILE, "DOCX"));

        assertEquals(SpecParseException.Kind.FAILED, e.kind());
        assertEquals("LLM이 요청을 거절해 변환하지 못했습니다 (sample.docx, claude)", e.getMessage());
        assertEquals(Optional.of(new Engine("llm", PROVIDER, MODEL, EFFORT, false, ONE_CALL)), e.engine());
        assertEquals(1, client.calls(), "거절은 JSON을 다시 받으려 다시 부르지 않는다");

        SpecParseResult result = parser(client, options().withCache(cache)).extract(BLOCKS, FILE, "DOCX");
        assertEquals(2, client.calls());
        assertFalse(result.engine().cacheUsed());
    }

    @Test
    void 길이_상한을_넘는_문서는_LLM에_보내지_않고_실패로_알린다() {
        ScriptedLlmClient client = new ScriptedLlmClient();
        LlmSpecParser parser = new LlmSpecParser(client, PROVIDER, MODEL, EFFORT, options(), null,
                new DocumentSerializer(100), LlmSpecParserTest::ruleEngine);

        SpecParseException e = assertThrows(SpecParseException.class, () -> parser.extract(BLOCKS, FILE, "DOCX"));

        assertEquals(SpecParseException.Kind.FAILED, e.kind());
        assertTrue(e.getMessage().startsWith("LLM에 보내지 않았습니다 (sample.docx): 직렬화한 문서가 길이 상한을 넘습니다"),
                e.getMessage());
        assertEquals(0, client.calls());
        assertEquals(Optional.empty(), e.engine(), "보내지 않았으므로 알릴 토큰이 없다");
    }

    // ---- JSON 파싱 실패 (설계 4.4) ----

    @Test
    void JSON으로_읽지_못하면_한_번_더_불러_쓰고_두_호출의_토큰을_더하며_읽힌_응답만_저장한다() throws Exception {
        ExtractionCache cache = ExtractionCache.at(tempDir.resolve("cache"));
        String valid = json(standardOperation());
        ScriptedLlmClient client = new ScriptedLlmClient().reply(complete("{\"serviceName\": "), complete(valid));

        SpecParseResult result = parser(client, options().withCache(cache)).extract(BLOCKS, FILE, "DOCX");

        assertEquals(2, client.calls());
        assertEquals(client.requests().get(0), client.requests().get(1), "같은 요청을 다시 보낸다");
        assertEquals(1, result.apis().size());
        assertEquals(new TokenUsage(2_000, 400, 600, 8_000), result.engine().tokenUsage());
        assertEquals(Optional.of(valid), cache.find(key(client.requests().get(0))).map(LlmResponse::json));
    }

    @Test
    void 다시_불러도_JSON으로_읽지_못하면_그_문서를_실패로_알리고_저장하지_않는다() throws IOException {
        Path directory = tempDir.resolve("cache");
        ScriptedLlmClient client = new ScriptedLlmClient().reply(complete("문서에서 다음 오퍼레이션을 찾았습니다."), complete("{}"));

        SpecParseException e = assertThrows(SpecParseException.class,
                () -> parser(client, options().withCache(ExtractionCache.at(directory))).extract(BLOCKS, FILE, "DOCX"));

        assertEquals(SpecParseException.Kind.FAILED, e.kind());
        assertTrue(e.getMessage().startsWith("LLM 응답을 중간 스키마 JSON으로 읽지 못했습니다 (sample.docx, 2번 시도): "),
                e.getMessage());
        assertEquals(2, client.calls(), "JSON 파싱 실패는 한 번만 다시 부른다");
        assertFalse(Files.exists(directory), "읽히지 않는 응답을 저장하면 다음 실행에서도 되풀이된다");
        assertEquals(Optional.of(new Engine("llm", PROVIDER, MODEL, EFFORT, false, new TokenUsage(2_000, 400, 600, 8_000))),
                e.engine(), "두 호출 모두 과금된다");
    }

    @Test
    void JSON을_읽지_못해_다시_부른_호출이_실패하면_앞_호출의_토큰을_예외에_담는다() {
        ScriptedLlmClient client = new ScriptedLlmClient().reply(complete("{\"serviceName\": "))
                .fail(new LlmException(LlmException.Kind.UNAVAILABLE, "Claude API 오류 (HTTP 529): overloaded_error"));

        SpecParseException e = assertThrows(SpecParseException.class,
                () -> parser(client, options()).extract(BLOCKS, FILE, "DOCX"));

        assertEquals(SpecParseException.Kind.RETRYABLE, e.kind());
        assertEquals(2, client.calls());
        assertEquals(Optional.of(new Engine("llm", PROVIDER, MODEL, EFFORT, false, ONE_CALL)), e.engine(),
                "첫 호출은 답을 받아 과금됐다");
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("schemaViolations")
    void 스키마를_따르지_않은_JSON도_읽지_못한_것으로_보고_다시_부른다(String violation, String json) throws Exception {
        ScriptedLlmClient client = new ScriptedLlmClient().reply(complete(json), complete(json(standardOperation())));

        SpecParseResult result = parser(client, options()).extract(BLOCKS, FILE, "DOCX");

        assertEquals(2, client.calls(), violation);
        assertEquals(1, result.apis().size());
    }

    static Stream<Arguments> schemaViolations() throws JsonProcessingException {
        String valid = json(standardOperation());
        return Stream.of(
                Arguments.of("빈 글", ""),
                Arguments.of("최상위 null", "null"),
                Arguments.of("마크다운 코드 블록", "```json\n" + valid + "\n```"),
                Arguments.of("JSON 뒤에 붙은 글", valid + "\n이상입니다."),
                Arguments.of("필드가 빠짐", edit(valid, tree -> tree.remove("documentNotes"))),
                Arguments.of("필드가 null", edit(valid, tree -> tree.putNull("provider"))),
                Arguments.of("모르는 필드", edit(valid, tree -> tree.put("confidence", "high"))),
                Arguments.of("모르는 enum 값", edit(valid, tree -> operation(tree).put("httpMethod", "FETCH"))),
                Arguments.of("숫자로 쓴 enum", edit(valid, tree -> operation(tree).put("httpMethod", 0))),
                Arguments.of("문자열 자리의 숫자", edit(valid, tree -> tree.put("serviceName", 1))),
                Arguments.of("불리언 자리의 글", edit(valid, tree ->
                        ((ObjectNode) operation(tree).get("requestParameters").get(0)).put("common", "false"))),
                Arguments.of("배열 안의 null", edit(valid, tree -> ((ArrayNode) tree.get("operations")).addNull())),
                Arguments.of("겹친 키", "{\"serviceName\":\"측정소\"," + valid.substring(1)));
    }

    // ---- 결과 캐시 (설계 5.4) ----

    @Test
    void 결과_캐시에_있으면_부르지_않고_캐시를_썼다고_알리며_같은_IR을_낸다() throws Exception {
        ExtractionCache cache = ExtractionCache.at(tempDir.resolve("cache"));
        ScriptedLlmClient client = new ScriptedLlmClient().reply(complete(json(standardOperation())));

        SpecParseResult first = parser(client, options().withCache(cache)).extract(BLOCKS, FILE, "DOCX");
        SpecParseResult second = parser(client, options().withCache(cache)).extract(BLOCKS, FILE, "DOCX");

        assertEquals(1, client.calls(), "캐시를 쓰면 부르지 않는다");
        assertEquals(new Engine("llm", PROVIDER, MODEL, EFFORT, false, ONE_CALL), first.engine());
        assertEquals(new Engine("llm", PROVIDER, MODEL, EFFORT, true, TokenUsage.NONE), second.engine(),
                "부르지 않았으므로 이번 실행에서 쓴 토큰이 없다");
        assertEquals(withoutParsedAt(first.apis().get(0).ir()), withoutParsedAt(second.apis().get(0).ir()),
                "같은 입력과 같은 설정에서 같은 IR을 낸다");
    }

    @Test
    void 결과_캐시를_끄면_매번_부른다() throws Exception {
        ScriptedLlmClient client = new ScriptedLlmClient()
                .reply(complete(json(standardOperation())), complete(json(standardOperation())));

        parser(client, options()).extract(BLOCKS, FILE, "DOCX");
        SpecParseResult second = parser(client, options()).extract(BLOCKS, FILE, "DOCX");

        assertEquals(2, client.calls());
        assertFalse(second.engine().cacheUsed());
    }

    @Test
    void 모델이_바뀌면_결과_캐시를_쓰지_않고_다시_부른다() throws Exception {
        ExtractionCache cache = ExtractionCache.at(tempDir.resolve("cache"));
        ScriptedLlmClient client = new ScriptedLlmClient()
                .reply(complete(json(standardOperation())), complete(json(standardOperation())));

        parser(client, options().withCache(cache)).extract(BLOCKS, FILE, "DOCX");
        SpecParseResult other = new LlmSpecParser(client, PROVIDER, "claude-sonnet-5-5", EFFORT, options().withCache(cache))
                .extract(BLOCKS, FILE, "DOCX");

        assertEquals(2, client.calls());
        assertFalse(other.engine().cacheUsed());
    }

    @Test
    void 캐시에서_꺼낸_응답이_읽히지_않으면_캐시를_보지_않고_다시_불러_바꿔_둔다() throws Exception {
        ExtractionCache cache = ExtractionCache.at(tempDir.resolve("cache"));
        String valid = json(standardOperation());
        ScriptedLlmClient client = new ScriptedLlmClient().reply(complete(valid));
        LlmRequest request = ExtractionPrompt.load().wholeDocumentRequest(new DocumentSerializer().serialize(BLOCKS),
                LlmSpecParser.DEFAULT_MAX_OUTPUT_TOKENS);
        cache.store(key(request), complete("{\"serviceName\": \"앞선 형식\"}"));

        SpecParseResult result = parser(client, options().withCache(cache)).extract(BLOCKS, FILE, "DOCX");

        assertEquals(1, client.calls());
        assertFalse(result.engine().cacheUsed(), "결과는 새로 부른 응답에서 나왔다");
        assertEquals(Optional.of(valid), cache.find(key(request)).map(LlmResponse::json));
    }

    @Test
    void 결과_캐시를_읽거나_쓰지_못해도_받은_결과를_쓰고_경고한다() throws Exception {
        // 캐시 파일 자리에 디렉터리가 있어 읽지도 바꾸지도 못한다
        Path directory = tempDir.resolve("cache");
        LlmRequest request = ExtractionPrompt.load().wholeDocumentRequest(new DocumentSerializer().serialize(BLOCKS),
                LlmSpecParser.DEFAULT_MAX_OUTPUT_TOKENS);
        Files.createDirectories(directory.resolve(key(request).sha256() + ".json"));
        ScriptedLlmClient client = new ScriptedLlmClient().reply(complete(json(standardOperation())));

        SpecParseResult result = parser(client, options().withCache(ExtractionCache.at(directory)))
                .extract(BLOCKS, FILE, "DOCX");

        assertEquals(1, result.apis().size(), "이미 과금한 응답을 캐시 때문에 버리지 않는다");
        assertEquals(2, warnings.size(), warnings.toString());
        assertTrue(warnings.get(0).startsWith("sample.docx: 결과 캐시를 읽지 못해 LLM을 부릅니다: "), warnings.get(0));
        assertTrue(warnings.get(1).startsWith("sample.docx: 결과 캐시에 쓰지 못했습니다: "), warnings.get(1));
    }

    // ---- 교차검증 (설계 5.3) ----

    @Test
    void rule_엔진과_다른_점은_교차검증_노트로_남기고_rule_엔진만_찾은_오퍼레이션은_경고한다() throws Exception {
        ScriptedLlmClient client = new ScriptedLlmClient()
                .reply(complete(json(standardOperation())), complete(json()));

        SpecParseResult checked = parser(client, options()).extract(BLOCKS, FILE, "DOCX");
        SpecParseResult none = parser(client, options()).extract(BLOCKS, FILE, "DOCX");

        // 표준 출력은 요청 표의 areaCode를 옮기지 않았다
        assertEquals(List.of("[교차검증] 요청 파라미터가 rule 엔진과 다름: rule에만 areaCode"),
                crossCheckNotes(checked.apis().get(0).ir()));
        assertEquals(List.of(), none.operations());
        assertEquals(List.of("sample.docx: rule 엔진만 찾은 오퍼레이션: getMsrstnList "
                + "(http://apis.data.go.kr/B552584/MsrstnInfoInqireSvc/getMsrstnList)"), warnings);
    }

    @Test
    void 교차검증을_끄면_rule_엔진을_돌리지_않고_노트도_경고도_없다() throws Exception {
        ScriptedLlmClient client = new ScriptedLlmClient().reply(complete(json(standardOperation())));
        LlmSpecParser parser = new LlmSpecParser(client, PROVIDER, MODEL, EFFORT, options().withCrossCheck(false), null,
                new DocumentSerializer(), (blocks, sourceFile, sourceFormat) -> {
                    throw new AssertionError("교차검증을 껐는데 rule 엔진을 돌렸다");
                });

        SpecParseResult result = parser.extract(BLOCKS, FILE, "DOCX");

        assertEquals(List.of(), crossCheckNotes(result.apis().get(0).ir()));
        assertEquals(List.of(), warnings);
    }

    @Test
    void rule_엔진이_실패해도_LLM_결과는_그대로_쓰고_경고한다() throws Exception {
        ScriptedLlmClient client = new ScriptedLlmClient()
                .reply(complete(json(standardOperation())), complete(json(standardOperation())));
        LlmSpecParser failing = new LlmSpecParser(client, PROVIDER, MODEL, EFFORT, options(), null, new DocumentSerializer(),
                (blocks, sourceFile, sourceFormat) -> {
                    throw new IllegalStateException("rule 엔진 고장");
                });

        SpecParseResult result = failing.extract(BLOCKS, FILE, "DOCX");
        SpecParseResult unchecked = parser(client, options().withCrossCheck(false)).extract(BLOCKS, FILE, "DOCX");

        assertEquals(withoutParsedAt(unchecked.apis().get(0).ir()), withoutParsedAt(result.apis().get(0).ir()));
        assertEquals(List.of("sample.docx: rule 엔진이 실패해 교차검증을 건너뜁니다: rule 엔진 고장"), warnings);
    }

    // ---- 팩토리와 API 키 ----

    @Test
    void 팩토리로_만들면_회사_모델_추론_깊이를_클라이언트와_같은_설정에서_읽는다() throws Exception {
        ScriptedLlmClient client = new ScriptedLlmClient().reply(complete(json(standardOperation())));
        List<LlmSettings> created = new ArrayList<>();
        LlmSettings settings = LlmSettings.of("fake-model-1", "high", "fake-key");

        SpecParseResult result = LlmSpecParser.of(factory(client, created), settings, options()).extract(BLOCKS, FILE, "DOCX");

        assertEquals(List.of(settings), created);
        assertEquals(new Engine("llm", "fake", "fake-model-1", "high", false, ONE_CALL), result.engine());
        JsonNode llm = result.apis().get(0).ir().get("metadata").get("llm");
        assertEquals("fake", llm.get("provider").asText());
        assertEquals("fake-model-1", llm.get("model").asText());
        assertEquals("high", llm.get("effort").asText());
    }

    @Test
    void 어댑터가_가리지_못한_API_키도_메시지와_원인_사슬에_남기지_않는다() {
        String apiKey = "sk-ant-api03-SECRET-test-key_0123456789";
        ScriptedLlmClient client = new ScriptedLlmClient().fail(new LlmException(LlmException.Kind.AUTH,
                "프록시 오류: x-api-key " + apiKey + "가 틀렸습니다", new IOException("머리 x-api-key: " + apiKey)));

        SpecParseException e = assertThrows(SpecParseException.class,
                () -> LlmSpecParser.of(factory(client, new ArrayList<>()), LlmSettings.of(MODEL, EFFORT, apiKey), options())
                        .extract(BLOCKS, FILE, "DOCX"));

        assertEquals(SpecParseException.Kind.AUTH, e.kind());
        assertTrue(e.getMessage().contains("x-api-key ****가 틀렸습니다"), e.getMessage());
        StringWriter trace = new StringWriter();
        e.printStackTrace(new PrintWriter(trace));
        assertFalse(trace.toString().contains(apiKey), trace.toString());
    }

    // ---- 도우미 ----

    private LlmSpecParser parser(LlmClient client, LlmSpecParser.Options options) {
        return new LlmSpecParser(client, PROVIDER, MODEL, EFFORT, options);
    }

    /** 결과 캐시는 끄고 경고는 모은다. 캐시를 보는 테스트는 임시 디렉터리로 바꾼다 */
    private LlmSpecParser.Options options() {
        return LlmSpecParser.Options.defaults().withCache(ExtractionCache.disabled()).withWarnings(warnings::add);
    }

    private SpecParseException failure(ScriptedLlmClient client) {
        return assertThrows(SpecParseException.class, () -> parser(client, options()).extract(BLOCKS, FILE, "DOCX"));
    }

    private static ExtractionCache.Key key(LlmRequest request) {
        return ExtractionCache.key(request, PROVIDER, MODEL, EFFORT);
    }

    private static RuleEngine.Result ruleEngine(List<Block> blocks, String sourceFile, String sourceFormat) {
        return new RuleEngine().assemble(blocks, sourceFile, sourceFormat);
    }

    /** 만든 설정을 기록하고 정해 둔 클라이언트를 돌려주는 회사 */
    private static LlmProviderFactory factory(LlmClient client, List<LlmSettings> created) {
        return new LlmProviderFactory() {
            @Override
            public String id() {
                return "fake";
            }

            @Override
            public String defaultModel() {
                return "fake-model-1";
            }

            @Override
            public String defaultEffort() {
                return "high";
            }

            @Override
            public List<String> apiKeyEnvironmentVariables() {
                return List.of("FAKE_API_KEY");
            }

            @Override
            public LlmClient create(LlmSettings settings) {
                created.add(settings);
                return client;
            }
        };
    }

    private static String json(LlmFixtures.OperationBuilder... operations) throws JsonProcessingException {
        return MAPPER.writeValueAsString(LlmFixtures.extraction("한국환경공단", List.of(), operations));
    }

    private static String edit(String json, Consumer<ObjectNode> change) throws JsonProcessingException {
        ObjectNode tree = (ObjectNode) MAPPER.readTree(json);
        change.accept(tree);
        return MAPPER.writeValueAsString(tree);
    }

    private static ObjectNode operation(ObjectNode extraction) {
        return (ObjectNode) extraction.get("operations").get(0);
    }

    private static ObjectNode withoutParsedAt(ObjectNode ir) {
        ObjectNode copy = ir.deepCopy();
        ((ObjectNode) copy.get("metadata")).remove("parsedAt");
        return copy;
    }

    private static List<String> crossCheckNotes(ObjectNode ir) {
        List<String> notes = new ArrayList<>();
        ir.get("metadata").get("reviewNotes").forEach(note -> {
            if (note.asText().startsWith("[교차검증]")) {
                notes.add(note.asText());
            }
        });
        return notes;
    }
}
