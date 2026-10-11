package kr.go.h2spec.parser.llm.normalize;

import com.fasterxml.jackson.databind.JsonNode;
import kr.go.h2spec.parser.Block;
import kr.go.h2spec.parser.RuleEngine;
import kr.go.h2spec.parser.llm.LlmFixtures.OperationBuilder;
import kr.go.h2spec.parser.llm.TestDocuments;
import kr.go.h2spec.parser.llm.schema.LlmExtraction;
import kr.go.h2spec.parser.llm.schema.LlmRequestParameter;
import kr.go.h2spec.parser.llm.schema.LlmResponseField;
import kr.go.h2spec.parser.llm.schema.LlmValueType;
import kr.go.h2spec.parser.llm.validate.ExtractionValidator;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static kr.go.h2spec.parser.llm.LlmFixtures.extraction;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 실제 기관 문서와 손으로 쓴 LLM 출력으로 정규화 결과를 본다. 설계 5.2의 성공코드 정책과 인증 규칙이 근거로 든 문서들이다.
 * 주소는 같은 Block 목록으로 돌린 rule 엔진의 주소와도 견준다. 같은 후보에서는 엔진이 달라도 같은 주소가 나와야 한다.
 */
class RealDocumentNormalizationTest {

    private static final LlmMetadata LLM = new LlmMetadata("claude", "claude-opus-5-5", "medium", "extraction-v2", "2");

    @Test
    void 부록_A_제주_와이파이는_요청_URL_문단의_주소를_쓰고_경로_키_때문에_지원_범위_밖으로_건너뛰되_IR을_돌려준다() {
        List<Block> blocks = TestDocuments.read("jejudatahub-wifi-usage.hwp");
        NormalizedOperation jeju = normalize(blocks, "jejudatahub-wifi-usage.hwp",
                TestDocuments.extraction("jejudatahub-wifi-usage.json"));
        JsonNode api = jeju.ir().get("api");

        // 주소: Call Back URL·표 안의 주소가 없고 오퍼레이션명도 없어 urlLine을 고르고 질의 문자열을 지운다. rule 엔진과 같다
        assertEquals("https://open.jejudatahub.net/api/proxy", api.get("baseUrl").asText());
        assertEquals("/7tttaDaD5ataaba75a57ttDtaDb6tb7t/{your_appkey}", api.get("endpoint").asText());
        assertEquals(ruleUrl(blocks), url(api));
        assertEquals("Api7tttaDaD5ataaba75a57ttDtaDb6tb7t", jeju.apiId());

        // 메서드: 문서에 표기가 없어 UNKNOWN이고 노트 없이 GET으로 둔다
        assertEquals("GET", api.get("httpMethod").asText());
        assertTrue(notes(jeju).stream().noneMatch(note -> note.contains("메서드") || note.contains("GET")), notes(jeju).toString());

        // 인증: 이름이 serviceKey인 파라미터가 없고 게이트웨이 주소도 아니라 3-1 전까지 건너뛴다. IR은 함께 돌려준다
        assertTrue(jeju.outOfScope());
        assertFalse(jeju.converted());
        assertTrue(jeju.skipReason().startsWith("인증 방식 미지원 (주소 경로의 {your_appkey})"), jeju.skipReason());
        assertTrue(jeju.skipReason().contains("응답 루트가 둘 이상 (totCnt, hasMore, data)"), jeju.skipReason());
        assertEquals("PATH_KEY", api.get("authType").asText());

        // 성공코드: 후보가 없어 00을 넣고 노트를 남긴다
        assertEquals("00", api.get("errorSpec").get("successResultCode").asText());

        // 응답은 문서의 의미대로다: JSON, 최상위 키 그대로의 경로, 예제에서 문자열인 값은 string
        assertEquals("JSON", api.get("responseFormat").asText());
        assertEquals(List.of("totCnt", "hasMore", "data[].baseDate", "data[].uploadBytes"), paths(jeju));
        assertEquals("integer", api.get("responseFields").get(0).get("type").asText());
        assertEquals("string", api.get("responseFields").get(3).get("type").asText());

        // 요청: 경로 변수는 필수 path, 공통 구간의 기본값은 IR 키 default
        assertEquals(List.of("your_appkey", "number", "limit", "searchDate"), names(jeju));
        JsonNode appKey = api.get("requestParameters").get(0);
        assertEquals("path", appKey.get("in").asText());
        assertTrue(appKey.get("required").asBoolean());
        assertEquals("1", api.get("requestParameters").get(1).get("default").asText());
        assertEquals("20190704", api.get("requestParameters").get(3).get("example").asText());

        assertEquals(List.of(
                "[LLM] data 배열 원소의 필드는 설명 표가 없어 응답 예제의 키와 값 모양으로 정했다",
                "[LLM] 문서에 제공 기관 이름이 없다",
                "[LLM] 인증키를 쿼리 serviceKey가 아니라 주소 경로({your_appkey})로 받는다",
                "[LLM] 응답 JSON의 최상위 키가 여럿이다 (totCnt, hasMore, data)",
                "[정규화] 문서에서 성공코드를 찾지 못해 00으로 둠"), notes(jeju));
        assertTrue(jeju.ir().get("metadata").get("manualReviewRequired").asBoolean());
    }

    @Test
    void 안전보건공단_스킴_없는_Call_Back_URL은_다른_후보가_없으면_rule_엔진처럼_https를_붙이고_노트를_남긴다() {
        List<Block> blocks = TestDocuments.read("kosha-certificate-check.hwp");
        LlmExtraction fixture = TestDocuments.extraction("kosha-certificate-check.json");
        String url = "https://portal.kosha.or.kr/openapi/v1/koshagw";

        // 요청 예제까지 옮긴 출력은 rule 엔진처럼 요청 예제의 주소를 쓰고 스킴 노트가 없다
        NormalizedOperation faithful = normalize(blocks, "kosha-certificate-check.hwp", fixture);
        assertEquals(url, url(faithful.ir().get("api")));
        assertEquals(ruleUrl(blocks), url(faithful.ir().get("api")));
        assertTrue(notes(faithful).stream().noneMatch(note -> note.contains("스킴")), notes(faithful).toString());

        // 요청 예제 후보가 없으면 스킴 없는 Call Back URL에 https://를 붙인다. rule 엔진과 같은 함수와 문구다
        NormalizedOperation schemeless = normalize(blocks, "kosha-certificate-check.hwp",
                extraction(OperationBuilder.from(fixture.operations().get(0)).requestExampleUrl("")));
        assertEquals(url, url(schemeless.ir().get("api")));
        assertTrue(notes(schemeless).contains(
                "[정규화] Call Back URL에 스킴이 없어 https://를 붙임: portal.kosha.or.kr/openapi/v1/koshagw"),
                notes(schemeless).toString());
        assertTrue(notes(schemeless).contains(
                "[정규화] 필수 여부를 알 수 없어 선택(false)으로 둔 요청 파라미터 (3개): serviceKey, callApiId, certNo"));

        // 루트가 둘(header, body)인 JSON이라 지원 범위 밖이지만 정규화한 IR은 돌려준다
        assertTrue(schemeless.outOfScope());
        assertEquals("응답 루트가 둘 이상 (header, body): 3단계 IR 확장 전까지 generator는 루트 하나로 감싼 응답만 받는다",
                schemeless.skipReason());
        assertEquals("SERVICE_KEY_QUERY_PARAM", schemeless.ir().get("api").get("authType").asText());
    }

    @Test
    void 선관위_성공코드_후보가_표_00과_예제_INFO_00이면_예제를_따라_INFO_00이고_노트를_남긴다() {
        NormalizedOperation nec = normalize(TestDocuments.read("nec-ballot-counting-place.hwpx"),
                "nec-ballot-counting-place.hwpx", TestDocuments.extraction("nec-ballot-counting-place.json"));

        assertTrue(nec.converted(), nec.skipReason());
        JsonNode errorSpec = nec.ir().get("api").get("errorSpec");
        assertEquals("INFO-00", errorSpec.get("successResultCode").asText());
        assertTrue(notes(nec).contains("[검증] 성공코드 후보: 00(응답 필드 표), INFO-00(응답 예제) → INFO-00 "
                + "(응답 예제 > 응답 필드 표 > 에러코드 표 순으로 고름)"), notes(nec).toString());
        List<String> codes = new ArrayList<>();
        errorSpec.get("knownErrorCodes").forEach(code -> codes.add(code.get("code").asText()));
        assertEquals(List.of("12", "30", "ERROR-03", "ERROR-310"), codes);
    }

    @Test
    void 기상청_성공코드_후보가_표_00과_예제_0이면_00이고_노트를_남긴다() {
        NormalizedOperation kma = normalize(TestDocuments.read("kma-mid-forecast.docx"), "kma-mid-forecast.docx",
                TestDocuments.extraction("kma-mid-forecast.json"));

        assertTrue(kma.converted(), kma.skipReason());
        assertEquals("00", kma.ir().get("api").get("errorSpec").get("successResultCode").asText());
        assertTrue(notes(kma).contains("[검증] 성공코드 후보: 00(응답 필드 표), 0(응답 예제) → 00 "
                + "(모든 자리가 0인 코드는 00으로 맞춤)"), notes(kma).toString());
        assertEquals("10", kma.ir().get("api").get("requestParameters").get(1).get("default").asText(),
                "설명의 Default: 10을 IR 키 default로 옮긴다");
    }

    @Test
    void 천문연구원_ServiceKey는_대소문자를_무시하고_인증키로_받는다() {
        List<Block> blocks = TestDocuments.read("kasi-special-day.docx");
        NormalizedOperation kasi = normalize(blocks, "kasi-special-day.docx", TestDocuments.extraction("kasi-special-day.json"));

        assertTrue(kasi.converted(), kasi.skipReason());
        assertEquals("SERVICE_KEY_QUERY_PARAM", kasi.ir().get("api").get("authType").asText());
        assertEquals(List.of("solYear", "solMonth", "ServiceKey", "_type", "numOfRows"), names(kasi),
                "게이트웨이 주소지만 serviceKey를 다시 보충하지 않는다");
        assertEquals(List.of("[LLM] 문서에 에러코드 표가 없다"), notes(kasi));
        assertEquals(ruleUrl(blocks), url(kasi.ir().get("api")));
    }

    @Test
    void 공통_구간과_겹치는_파라미터와_필드는_오퍼레이션_것을_남긴다() {
        // 제주 문서의 공통 구간(T14·T20)에 있는 number와 totCnt를 오퍼레이션이 다시 적은 경우
        LlmExtraction fixture = TestDocuments.extraction("jejudatahub-wifi-usage.json");
        OperationBuilder operation = OperationBuilder.from(fixture.operations().get(0))
                .addParameter(new LlmRequestParameter("number", LlmRequestParameter.Location.QUERY, LlmValueType.INTEGER,
                        LlmRequestParameter.Requirement.OPTIONAL, "오퍼레이션이 다시 적은 페이지 번호", "", "1", false))
                .addField(new LlmResponseField("totCnt", LlmValueType.INTEGER, "오퍼레이션이 다시 적은 총 개수", "13",
                        LlmResponseField.PathSource.RESPONSE_EXAMPLE, false));

        NormalizedOperation jeju = normalize(TestDocuments.read("jejudatahub-wifi-usage.hwp"), "jejudatahub-wifi-usage.hwp",
                extraction(operation));

        assertEquals(List.of("your_appkey", "limit", "searchDate", "number"), names(jeju));
        assertEquals("오퍼레이션이 다시 적은 페이지 번호",
                jeju.ir().get("api").get("requestParameters").get(3).get("description").asText());
        assertEquals(List.of("hasMore", "data[].baseDate", "data[].uploadBytes", "totCnt"), paths(jeju));
        assertEquals("오퍼레이션이 다시 적은 총 개수", jeju.ir().get("api").get("responseFields").get(3).get("description").asText());
    }

    private NormalizedOperation normalize(List<Block> blocks, String sourceFile, LlmExtraction extraction) {
        return new IrNormalizer(LLM).normalize(new ExtractionValidator().validate(extraction, blocks), sourceFile,
                TestDocuments.sourceFormat(sourceFile)).get(0);
    }

    /** 같은 Block 목록으로 돌린 rule 엔진의 첫 오퍼레이션 주소 */
    private String ruleUrl(List<Block> blocks) {
        RuleEngine.Result rule = new RuleEngine().assemble(blocks, "rule", "HWP");
        return url(rule.apis().get(0).ir().get("api"));
    }

    private String url(JsonNode api) {
        return api.get("baseUrl").asText() + api.get("endpoint").asText();
    }

    private List<String> notes(NormalizedOperation operation) {
        List<String> notes = new ArrayList<>();
        operation.ir().get("metadata").get("reviewNotes").forEach(note -> notes.add(note.asText()));
        return notes;
    }

    private List<String> names(NormalizedOperation operation) {
        List<String> names = new ArrayList<>();
        operation.ir().get("api").get("requestParameters").forEach(parameter -> names.add(parameter.get("name").asText()));
        return names;
    }

    private List<String> paths(NormalizedOperation operation) {
        List<String> paths = new ArrayList<>();
        operation.ir().get("api").get("responseFields").forEach(field -> paths.add(field.get("path").asText()));
        return paths;
    }
}
