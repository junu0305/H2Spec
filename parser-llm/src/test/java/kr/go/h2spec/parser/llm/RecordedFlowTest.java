package kr.go.h2spec.parser.llm;

import com.fasterxml.jackson.databind.node.ObjectNode;
import kr.go.h2spec.generator.CodeGenerator;
import kr.go.h2spec.generator.ir.IrLoader;
import kr.go.h2spec.parser.ParsedApi;
import kr.go.h2spec.parser.SpecParseException;
import kr.go.h2spec.parser.SpecParseResult;
import kr.go.h2spec.parser.SpecParseResult.Engine;
import kr.go.h2spec.parser.SpecParseResult.Operation;
import kr.go.h2spec.parser.SpecParseResult.TokenUsage;
import kr.go.h2spec.parser.llm.client.LlmResponse;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 녹화한 LLM 응답으로 llm 엔진의 전체 흐름을 문서마다 끝까지 돌린다. 키 없이 {@code ./gradlew build}에서 돈다.
 * <p>
 * {@link LlmSpecParser}가 문서를 읽어 직렬화 → 녹화 재생({@link RecordedLlmClient}) → 검증 → 정규화 → 교차검증을 거쳐 낸 결과에서,
 * 변환한 오퍼레이션은 IrLoader → CodeGenerator → 생성 코드 javac 컴파일까지 돌린다. CLI는 생성 코드를 컴파일하지 않으므로
 * 컴파일되지 않는 IR은 여기서 드러난다. 녹화에 없는 오퍼레이션을 rule 엔진만 찾으면 경고가 나므로 그 수도 문서마다 확인한다.
 */
class RecordedFlowTest {

    private static final String MODEL = "claude-opus-5-5";
    private static final String EFFORT = "medium";

    @TempDir
    Path tempDir;

    private final List<String> warnings = new ArrayList<>();

    /** 문서, 변환한 apiId, 지원 범위 밖으로 건너뛴 apiId, 교차검증 노트 수, rule 엔진만 찾은 오퍼레이션 수 */
    static Stream<Arguments> recordings() {
        return Stream.of(
                Arguments.of("its-traffic-light.hwp", List.of("TrafficLightList4"), List.of(), 0, 0),
                Arguments.of("jejudatahub-wifi-usage.hwp", List.of(), List.of("Api7tttaDaD5ataaba75a57ttDtaDb6tb7t"), 1, 0),
                Arguments.of("kasi-special-day.docx",
                        List.of("HoliDeInfo", "RestDeInfo", "AnniversaryInfo", "Get24DivisionsInfo", "SundryDayInfo"), List.of(), 2, 0),
                Arguments.of("kci-reference.docx", List.of("OpenApiD272List", "OpenApiD273List", "OpenApiD321List",
                        "OpenApiM320List", "OpenApiM325List", "OpenApiM326List"), List.of(), 0, 0),
                Arguments.of("kma-mid-forecast.docx", List.of("MidFcst", "MidLandFcst", "MidTa", "MidSeaFcst"), List.of(), 2, 0),
                Arguments.of("kosha-certificate-check.hwp", List.of(), List.of("Koshagw"), 1, 0),
                Arguments.of("msrstn-info.docx", List.of("MsrstnList", "NearbyMsrstnList", "TMStdrCrdnt"), List.of(), 0, 0),
                Arguments.of("nec-ballot-counting-place.hwpx", List.of("CountingSttnInfoInqire"), List.of(), 1, 0),
                Arguments.of("nps-disability-review.hwp", List.of("JgmtSttusInfoSearchV2"), List.of(), 1, 0));
    }

    @Test
    void 녹화한_모든_문서를_전체_흐름으로_돌린다() {
        assertEquals(TestDocuments.names(), recordings().map(arguments -> (String) arguments.get()[0]).sorted().toList(),
                "녹화를 더하면 여기 기대값도 더한다");
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("recordings")
    void 녹화한_응답을_끝까지_돌려_변환한_IR은_generator를_거쳐_컴파일된다(String document, List<String> converted,
                                                       List<String> outOfScope, int crossCheckNotes,
                                                       int ruleOnly) throws Exception {
        RecordedLlmClient client = RecordedLlmClient.of(TestDocuments.PROVIDER, document);

        SpecParseResult result = parser(client).extract(TestDocuments.path(document));

        assertEquals(1, client.requests().size(), "문서당 한 번 부른다");
        assertEquals(new Engine("llm", TestDocuments.PROVIDER, MODEL, EFFORT, false, tokens(RecordedLlmClient.USAGE)),
                result.engine());
        assertEquals(converted, result.apis().stream().map(ParsedApi::apiId).toList());
        assertEquals(outOfScope, result.operations().stream().filter(Operation::outOfScope)
                .map(operation -> operation.api().apiId()).toList(), "지원 범위 밖으로 건너뛴 오퍼레이션도 정규화한 IR을 담는다");
        assertEquals(result.operations().size() - converted.size(), result.skipped().size(),
                "변환하지 못한 오퍼레이션은 모두 이유와 함께 알린다");
        for (Operation operation : result.operations()) {
            if (operation.api() != null) {
                assertEquals("llm", operation.api().ir().get("metadata").get("engine").asText());
            }
        }
        assertEquals(crossCheckNotes, result.operations().stream()
                .filter(operation -> operation.api() != null)
                .mapToLong(operation -> crossCheckNotes(operation.api().ir()).size()).sum());
        String sourceFile = TestDocuments.path(document).getFileName().toString();
        assertEquals(ruleOnly, warnings.size(), warnings.toString());
        warnings.forEach(warning -> assertTrue(warning.startsWith(sourceFile + ": rule 엔진만 찾은 오퍼레이션: "), warning));

        List<Path> written = new ArrayList<>();
        for (ParsedApi api : result.apis()) {
            Path ir = Files.writeString(tempDir.resolve(api.apiId() + ".json"), api.ir().toPrettyString());
            written.addAll(new CodeGenerator().generate(new IrLoader().load(ir), tempDir.resolve("generated")));
        }
        if (!written.isEmpty()) {
            GeneratedSources.assertCompiles(written, tempDir.resolve("classes"));
        }
    }

    @Test
    void 제주_와이파이는_지원_범위_밖으로_건너뛰되_정규화한_IR을_담고_apis에는_넣지_않으며_parse는_명세가_없다고_하지_않고_실패를_알린다() throws Exception {
        String document = "jejudatahub-wifi-usage.hwp";

        SpecParseResult result = parser(RecordedLlmClient.of(TestDocuments.PROVIDER, document))
                .extract(TestDocuments.path(document));

        Operation jeju = result.operations().get(0);
        assertTrue(jeju.outOfScope());
        assertTrue(jeju.skipReason().startsWith("인증 방식 미지원 (주소 경로의 {your_appkey})"), jeju.skipReason());
        assertTrue(jeju.skipReason().contains("응답 루트가 둘 이상 (totCnt, hasMore, data)"), jeju.skipReason());
        assertEquals("JSON", jeju.api().ir().get("api").get("responseFormat").asText(), "IR은 문서의 의미대로 정규화했다");
        assertEquals(List.of(), result.apis(), "지원 범위 밖 IR은 파일로 쓰지 않는다");
        assertEquals(List.of("읍면동별 와이파이 사용량 API: " + jeju.skipReason()), result.skipped(), "CLI가 건너뛴 오퍼레이션을 알리는 꼴");

        SpecParseException e = assertThrows(SpecParseException.class,
                () -> parser(RecordedLlmClient.of(TestDocuments.PROVIDER, document)).parse(TestDocuments.path(document)));
        assertEquals(SpecParseException.Kind.FAILED, e.kind());
        assertEquals("변환할 수 있는 상세기능이 없습니다 (찾은 1개 모두 지원 범위 밖이거나 검증에서 건너뜀): "
                + "jejudatahub-wifi-usage.hwp (" + result.skipped().get(0) + ")", e.getMessage());
    }

    private LlmSpecParser parser(RecordedLlmClient client) {
        return new LlmSpecParser(client, TestDocuments.PROVIDER, MODEL, EFFORT, options());
    }

    /** 녹화는 문서마다 응답이 하나라, 검증 결과를 알려 다시 불러도 같은 응답이 온다. 그래서 다시 부르기는 끈다 */
    private LlmSpecParser.Options options() {
        return LlmSpecParser.Options.defaults().withCache(ExtractionCache.disabled()).withWarnings(warnings::add)
                .withRetryOnValidation(false);
    }

    private static TokenUsage tokens(LlmResponse.Usage usage) {
        return new TokenUsage(usage.inputTokens(), usage.cacheReadInputTokens(), usage.cacheWriteInputTokens(),
                usage.outputTokens());
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
