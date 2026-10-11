package kr.go.h2spec.parser.llm;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import kr.go.h2spec.parser.Block;
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
import kr.go.h2spec.parser.llm.schema.LlmResponseField;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.CsvSource;

import java.io.IOException;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.function.Consumer;
import java.util.stream.Stream;

import static kr.go.h2spec.parser.llm.SampleDocument.standardOperation;
import static kr.go.h2spec.parser.llm.ScriptedLlmClient.complete;
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
    void 출력이_잘리면_그_문서를_실패로_알리고_잘림_표시를_남겨_같은_요청은_다시_부르지_않는다() throws Exception {
        ExtractionCache cache = ExtractionCache.at(tempDir.resolve("cache"));
        ScriptedLlmClient client = new ScriptedLlmClient().reply(truncated(128_000));

        SpecParseException e = assertThrows(SpecParseException.class,
                () -> parser(client, options().withCache(cache)).extract(BLOCKS, FILE, "DOCX"));

        assertEquals(SpecParseException.Kind.FAILED, e.kind());
        assertEquals("LLM 출력이 출력 한도 128000 토큰에 걸려 잘렸습니다 (sample.docx, 출력 128000 토큰, thinking 포함). "
                + "나눠 부르기가 들어오기 전에는 출력이 잘리는 문서를 llm 엔진으로 변환하지 못합니다", e.getMessage());
        assertEquals(Optional.of(new Engine("llm", PROVIDER, MODEL, EFFORT, false, new TokenUsage(1_000, 0, 0, 128_000))),
                e.engine(), "잘린 출력도 과금된다");

        SpecParseException again = assertThrows(SpecParseException.class,
                () -> parser(client, options().withCache(cache)).extract(BLOCKS, FILE, "DOCX"));
        assertEquals(1, client.calls(), "잘림 표시가 있으면 다시 부르지(과금하지) 않는다");
        assertEquals(SpecParseException.Kind.FAILED, again.kind());
        assertTrue(again.getMessage().contains("결과 캐시에 잘림 표시가 있어 다시 부르지 않음"), again.getMessage());
        assertEquals(Optional.of(new Engine("llm", PROVIDER, MODEL, EFFORT, true, TokenUsage.NONE)), again.engine());

        // 출력 한도를 바꾸면 키가 달라져 다시 부른다
        client.reply(complete(json(standardOperation())));
        SpecParseResult result = parser(client, options().withCache(cache).withMaxOutputTokens(64_000))
                .extract(BLOCKS, FILE, "DOCX");
        assertEquals(2, client.calls());
        assertEquals(1, result.apis().size());
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

    // ---- 검증 결과를 알려 다시 부르기 ----

    @Test
    void 원문_대조에서_버린_것이_있으면_알려_한_번_더_부르고_더_나은_결과를_쓰며_토큰을_더한다() throws Exception {
        // 예제에 없는 감싸는 칸(result)을 지어내 응답 필드를 모두 버리고 오퍼레이션을 건너뛴 응답
        String invented = json(standardOperation().fields(
                LlmFixtures.field("result.header.resultCode", LlmResponseField.PathSource.RESPONSE_EXAMPLE)));
        ScriptedLlmClient client = new ScriptedLlmClient().reply(complete(invented), complete(json(standardOperation())));

        SpecParseResult result = parser(client, options()).extract(BLOCKS, FILE, "DOCX");

        assertEquals(2, client.calls());
        String instruction = client.requests().get(1).instruction();
        assertTrue(instruction.startsWith(client.requests().get(0).instruction()), "처음 지시 뒤에 검증 결과를 붙인다");
        assertTrue(instruction.contains("- getMsrstnList: 응답 필드 1개를 버림 — "), instruction);
        assertTrue(instruction.contains("result.header.resultCode"), instruction);
        assertTrue(instruction.contains("- getMsrstnList: 오퍼레이션을 건너뜀 — 검증을 통과한 응답 필드가 없습니다"), instruction);
        assertEquals(client.requests().get(0).document(), client.requests().get(1).document());
        assertEquals(1, result.apis().size(), "다시 부른 결과가 더 많이 통과해 그것을 쓴다");
        assertEquals(new TokenUsage(2_000, 400, 600, 8_000), result.engine().tokenUsage());
        assertEquals(List.of(FILE + ": 원문 대조에서 버린 항목이 있어 검증 결과를 알려 한 번 더 불렀습니다 "
                + "(통과한 오퍼레이션 0개 → 1개, 다시 부른 결과를 씀)"), warnings);
    }

    @Test
    void 다시_부른_결과가_낫지_않으면_처음_결과를_쓴다() throws Exception {
        String partial = json(standardOperation().fields(
                LlmFixtures.field("response.header.resultCode", LlmResponseField.PathSource.RESPONSE_EXAMPLE),
                LlmFixtures.field("result.header.resultMsg", LlmResponseField.PathSource.RESPONSE_EXAMPLE)));
        String worse = json(standardOperation().fields(
                LlmFixtures.field("result.header.resultCode", LlmResponseField.PathSource.RESPONSE_EXAMPLE)));
        ScriptedLlmClient client = new ScriptedLlmClient().reply(complete(partial), complete(worse));

        SpecParseResult result = parser(client, options()).extract(BLOCKS, FILE, "DOCX");

        assertEquals(2, client.calls());
        assertEquals(1, result.apis().size(), "다시 부른 결과는 오퍼레이션을 건너뛰어 처음 결과를 쓴다");
        assertTrue(warnings.get(0).endsWith("(통과한 오퍼레이션 1개 → 0개, 처음 결과를 씀)"), warnings.get(0));
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

    // ---- 교차검증 (설계 5.3) ----

    // ---- 팩토리와 API 키 ----

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
}
