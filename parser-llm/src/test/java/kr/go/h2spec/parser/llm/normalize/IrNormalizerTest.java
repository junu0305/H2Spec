package kr.go.h2spec.parser.llm.normalize;

import com.fasterxml.jackson.databind.JsonNode;
import kr.go.h2spec.generator.ir.IrLoader;
import kr.go.h2spec.parser.Block;
import kr.go.h2spec.parser.IrConventions;
import kr.go.h2spec.parser.llm.ExtractionPrompt;
import kr.go.h2spec.parser.llm.LlmFixtures;
import kr.go.h2spec.parser.llm.SampleDocument;
import kr.go.h2spec.parser.llm.schema.LlmAuth;
import kr.go.h2spec.parser.llm.schema.LlmExtraction;
import kr.go.h2spec.parser.llm.schema.LlmOperation.DefaultResponseFormat;
import kr.go.h2spec.parser.llm.schema.LlmOperation.HttpMethod;
import kr.go.h2spec.parser.llm.schema.LlmOperation.ResponseExampleFormat;
import kr.go.h2spec.parser.llm.schema.LlmOperation.ResponseFormat;
import kr.go.h2spec.parser.llm.schema.LlmOperation.ResponseStructure;
import kr.go.h2spec.parser.llm.schema.LlmRequestParameter;
import kr.go.h2spec.parser.llm.schema.LlmRequestParameter.Location;
import kr.go.h2spec.parser.llm.schema.LlmRequestParameter.Requirement;
import kr.go.h2spec.parser.llm.schema.LlmSuccessCode;
import kr.go.h2spec.parser.llm.schema.LlmValueType;
import kr.go.h2spec.parser.llm.validate.ExtractionValidator;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static kr.go.h2spec.parser.llm.LlmFixtures.extraction;
import static kr.go.h2spec.parser.llm.LlmFixtures.field;
import static kr.go.h2spec.parser.llm.LlmFixtures.operation;
import static kr.go.h2spec.parser.llm.LlmFixtures.parameter;
import static kr.go.h2spec.parser.llm.LlmFixtures.query;
import static kr.go.h2spec.parser.llm.SampleDocument.HEADER;
import static kr.go.h2spec.parser.llm.SampleDocument.heading;
import static kr.go.h2spec.parser.llm.SampleDocument.row;
import static kr.go.h2spec.parser.llm.SampleDocument.standardOperation;
import static kr.go.h2spec.parser.llm.SampleDocument.table;
import static kr.go.h2spec.parser.llm.schema.LlmResponseField.PathSource.FIELD_TABLE;
import static kr.go.h2spec.parser.llm.schema.LlmResponseField.PathSource.RESPONSE_EXAMPLE;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 정규화 표(설계 5.2)의 행마다 본다. 문서는 {@link SampleDocument}의 작은 표준 기술문서와, 주소·인증키가 다른 작은 문서다.
 * 정규화는 검증 결과를 받으므로 테스트도 검증을 거친 결과를 정규화한다.
 */
class IrNormalizerTest {

    private static final LlmMetadata LLM = new LlmMetadata("claude", "claude-opus-5-5", "medium", "extraction-v2", "2");
    private static final String ORGANIZATION_URL = "https://openapi.example.kr/svc/getList";
    private static final String RESPONSE = """
            <response>
            <header>
            <resultCode>00</resultCode>
            </header>
            <body>
            <items>
            <item>
            <itemName>종로</itemName>
            </item>
            </items>
            </body>
            </response>""";

    @TempDir
    Path tempDir;

    // ---- metadata, IR 모양 ----

    @Test
    void metadata는_rule_엔진과_같은_값에_엔진과_실행_설정을_더하고_토큰_수는_적지_않는다() {
        JsonNode metadata = normalize(standardOperation()).ir().get("metadata");

        assertEquals(List.of("sourceFile", "sourceFormat", "parsedAt", "parserVersion", "engine", "llm",
                "manualReviewRequired", "reviewNotes"), keys(metadata), "설계 8장의 예시와 같은 자리");
        assertEquals("sample.docx", metadata.get("sourceFile").asText());
        assertEquals("DOCX", metadata.get("sourceFormat").asText());
        assertEquals("llm", metadata.get("engine").asText());
        assertEquals(LLM.toJson(), metadata.get("llm"));
        assertEquals(List.of("provider", "model", "effort", "promptVersion", "schemaVersion"), keys(metadata.get("llm")));
        assertFalse(metadata.toString().toLowerCase().contains("token"), "토큰 수는 실행마다 달라 IR에 넣지 않는다");
        assertFalse(metadata.toString().toLowerCase().contains("cache"));
    }

    @Test
    void 프롬프트와_스키마_버전은_리소스에서_읽은_값으로_채운다() {
        LlmMetadata metadata = LlmMetadata.of("claude", "claude-opus-5-5", "high", ExtractionPrompt.load());

        assertEquals("extraction-v2", metadata.promptVersion());
        assertEquals("2", metadata.schemaVersion());
        assertEquals("", new LlmMetadata(null, null, null, null, null).toJson().get("effort").asText());
    }

    @Test
    void IR은_rule_엔진과_같은_모양이고_generator가_받는다() throws Exception {
        NormalizedOperation normalized = normalize(standardOperation());

        assertTrue(normalized.converted());
        assertEquals("MsrstnList", normalized.apiId());
        assertEquals(List.of("metadata", "api", "generatorHints"), keys(normalized.ir()));
        assertEquals(List.of("apiId", "apiName", "provider", "baseUrl", "endpoint", "httpMethod", "description",
                "authType", "responseFormat", "requestParameters", "responseFields", "errorSpec"), keys(api(normalized)));
        assertEquals(IrConventions.generatorHints("MsrstnList"), normalized.ir().get("generatorHints"));

        Path file = tempDir.resolve("MsrstnList.json");
        Files.writeString(file, normalized.ir().toPrettyString());
        assertEquals("MsrstnList", new IrLoader().load(file).api().apiId());
    }

    // ---- api.apiName, description, provider ----

    @Test
    void apiName은_국문_기능명이고_비었거나_영문_식별자거나_N_A면_apiId다() {
        assertEquals("측정소 목록 조회", api(normalize(standardOperation())).get("apiName").asText());
        assertEquals("MsrstnList", api(normalize(standardOperation().title("getMsrstnList"))).get("apiName").asText());
        assertEquals("MsrstnList", api(normalize(standardOperation().title("N/A"))).get("apiName").asText());
        assertEquals("MsrstnList", api(normalize(standardOperation().title(""))).get("apiName").asText());
    }

    @Test
    void 설명은_한_줄로_잇고_제공_기관은_LLM_결과의_값이다() {
        NormalizedOperation normalized = normalize(SampleDocument.standard(), extraction("한국환경공단", List.of(),
                standardOperation().description("측정소 목록을<br>조회하는 기능")));

        assertEquals("측정소 목록을 조회하는 기능", api(normalized).get("description").asText());
        assertEquals("한국환경공단", api(normalized).get("provider").asText());
    }

    // ---- api.httpMethod ----

    @Test
    void 메서드_표기가_없으면_노트_없이_GET이고_GET_없이_다른_메서드만_밝혔으면_지원_범위_밖이다() {
        NormalizedOperation unknown = normalize(standardOperation().method(HttpMethod.UNKNOWN));
        assertEquals("GET", api(unknown).get("httpMethod").asText());
        assertEquals(List.of(), notes(unknown));
        assertTrue(unknown.converted());

        assertEquals("GET", api(normalize(standardOperation().method(HttpMethod.GET))).get("httpMethod").asText());

        for (HttpMethod method : List.of(HttpMethod.POST, HttpMethod.OTHER)) {
            NormalizedOperation other = normalize(standardOperation().method(method));
            assertTrue(other.outOfScope(), method.name());
            assertEquals(method.name(), api(other).get("httpMethod").asText(), "지원 범위 밖 IR은 문서의 메서드를 담는다");
            assertTrue(other.skipReason().startsWith("GET이 아닌 메서드만 밝힌 오퍼레이션 (" + method + ")"), other.skipReason());
        }
    }

    // ---- api.authType, serviceKey ----

    @Test
    void 게이트웨이_주소에_serviceKey가_없으면_auth와_상관없이_보충한다() {
        NormalizedOperation normalized = normalize(standardOperation()
                .auth(LlmAuth.Kind.NONE, "").parameters(query("returnType"), query("numOfRows")));

        assertTrue(normalized.converted());
        assertEquals(List.of("serviceKey", "returnType", "numOfRows"), parameterNames(normalized));
        assertEquals("SERVICE_KEY_QUERY_PARAM", api(normalized).get("authType").asText());
        assertEquals(List.of("[정규화] " + IrConventions.GATEWAY_SERVICE_KEY_NOTE), notes(normalized));
    }

    @Test
    void 보충한_serviceKey는_경로_변수_다음에_오고_경로_변수는_주소에서_채운다() {
        String url = "http://apis.data.go.kr/1234567/TestSvc/getList/{areaCode}";
        NormalizedOperation normalized = normalize(document(url, row("pageNo", "페이지 번호", "4", "0", "1", "페이지 번호")),
                extraction(documentOperation(url, query("pageNo"))));

        assertEquals(List.of("areaCode", "serviceKey", "pageNo"), parameterNames(normalized), "rule 엔진과 같은 자리");
        JsonNode areaCode = api(normalized).get("requestParameters").get(0);
        assertEquals("path", areaCode.get("in").asText());
        assertTrue(areaCode.get("required").asBoolean());
        assertEquals("주소의 {areaCode} 자리에 넣는 값", areaCode.get("description").asText());
        assertEquals("/getList/{areaCode}", api(normalized).get("endpoint").asText());
    }

    @Test
    void 게이트웨이가_아니면_이름이_serviceKey인_쿼리_파라미터가_대소문자_무시하고_있어야_받는다() {
        NormalizedOperation serviceKey = normalize(document(ORGANIZATION_URL, row("ServiceKey", "인증키", "100", "1", "", "")),
                extraction(documentOperation(ORGANIZATION_URL, query("ServiceKey"))));
        assertTrue(serviceKey.converted());
        assertEquals("SERVICE_KEY_QUERY_PARAM", api(serviceKey).get("authType").asText());
        assertEquals(List.of("ServiceKey"), parameterNames(serviceKey), "게이트웨이가 아니라 보충하지 않는다");

        NormalizedOperation apiKey = normalize(document(ORGANIZATION_URL, row("apiKey", "인증키", "100", "1", "", "")),
                extraction(documentOperation(ORGANIZATION_URL, query("apiKey")).auth(LlmAuth.Kind.SERVICE_KEY_QUERY, "apiKey")));
        assertTrue(apiKey.outOfScope());
        assertEquals("인증 방식 미지원 (쿼리 파라미터 apiKey): generator는 이름이 serviceKey인 쿼리 파라미터만 인증키로 받는다"
                + " (3-1 전까지)", apiKey.skipReason());
        assertEquals("SERVICE_KEY_QUERY", api(apiKey).get("authType").asText());
    }

    @Test
    void 경로_키_헤더_키_인증_없음은_3_1_전까지_지원_범위_밖이다() {
        NormalizedOperation none = normalize(document(ORGANIZATION_URL), extraction(documentOperation(ORGANIZATION_URL)
                .auth(LlmAuth.Kind.NONE, "")));
        assertTrue(none.skipReason().startsWith("인증 방식 미지원 (인증키 없음)"), none.skipReason());

        NormalizedOperation header = normalize(document(ORGANIZATION_URL, row("X-Api-Key", "인증키", "100", "1", "", "")),
                extraction(documentOperation(ORGANIZATION_URL,
                        parameter("X-Api-Key", Location.HEADER, LlmValueType.STRING, Requirement.REQUIRED, "", "", false))
                        .auth(LlmAuth.Kind.HEADER_KEY, "X-Api-Key")));
        assertEquals("요청 파라미터 위치 미지원 (헤더: X-Api-Key): generator는 query·path 파라미터만 받는다; "
                + "인증 방식 미지원 (헤더의 X-Api-Key): generator는 이름이 serviceKey인 쿼리 파라미터만 인증키로 받는다 (3-1 전까지)",
                header.skipReason(), "이유가 여럿이면 모두 적는다");
    }

    // ---- api.requestParameters ----

    @Test
    void 요청_파라미터의_위치와_타입은_소문자로_옮기고_예시와_기본값은_빈_값이_아닐_때만_넣는다() {
        NormalizedOperation normalized = normalize(standardOperation().parameters(
                parameter("serviceKey", Location.QUERY, LlmValueType.STRING, Requirement.REQUIRED, "인증키", "", false),
                parameter("numOfRows", Location.QUERY, LlmValueType.INTEGER, Requirement.OPTIONAL, "10", "10", false),
                parameter("pageNo", Location.QUERY, LlmValueType.INTEGER, Requirement.UNKNOWN, "-", "", false),
                parameter("stationName", Location.QUERY, LlmValueType.STRING, Requirement.OPTIONAL, "", "", false)));

        JsonNode parameters = api(normalized).get("requestParameters");
        assertEquals(List.of("name", "in", "type", "required", "description", "example"), keys(parameters.get(0)));
        assertEquals("query", parameters.get(0).get("in").asText());
        assertEquals("string", parameters.get(0).get("type").asText());
        assertTrue(parameters.get(0).get("required").asBoolean());
        assertEquals("integer", parameters.get(1).get("type").asText());
        assertEquals("10", parameters.get(1).get("example").asText());
        assertEquals("10", parameters.get(1).get("default").asText(), "defaultValue의 IR 키는 default다");
        assertFalse(parameters.get(2).get("required").asBoolean(), "필수 여부를 모르면 false");
        assertFalse(parameters.get(2).has("example"), "값이 없음을 뜻하는 -는 넣지 않는다");
        assertFalse(parameters.get(3).has("example"));
        assertFalse(parameters.get(3).has("default"));
        assertEquals(List.of("[정규화] 필수 여부를 알 수 없어 선택(false)으로 둔 요청 파라미터 (1개): pageNo"), notes(normalized));
    }

    @Test
    void 숫자_타입인데_예시나_기본값이_앞자리_0이면_string으로_바꾸고_노트를_남긴다() {
        NormalizedOperation normalized = normalize(standardOperation().parameters(query("serviceKey"),
                parameter("areaCode", Location.QUERY, LlmValueType.INTEGER, Requirement.OPTIONAL, "0311", "", false),
                parameter("numOfRows", Location.QUERY, LlmValueType.NUMBER, Requirement.OPTIONAL, "", "007", false),
                parameter("pageNo", Location.QUERY, LlmValueType.INTEGER, Requirement.OPTIONAL, "0", "", false)));

        JsonNode parameters = api(normalized).get("requestParameters");
        assertEquals("string", parameters.get(1).get("type").asText());
        assertEquals("string", parameters.get(2).get("type").asText());
        assertEquals("integer", parameters.get(3).get("type").asText(), "0 하나는 앞자리 0이 아니다");
        assertEquals(List.of("[정규화] 앞자리 0이 있는 숫자 값이라 string으로 둔 항목 (2개): areaCode, numOfRows"),
                notes(normalized));
    }

    @Test
    void 주소에_없는_경로_변수_파라미터는_제거하고_주소의_경로_변수는_필수_path로_둔다() {
        // areaCode는 요청 표에 있지만 고른 주소(…/getMsrstnList)에 {areaCode} 자리가 없다
        NormalizedOperation removed = normalize(standardOperation().addParameter(
                parameter("areaCode", Location.PATH, LlmValueType.STRING, Requirement.REQUIRED, "", "", false)));
        assertEquals(List.of("serviceKey", "returnType", "numOfRows", "pageNo", "stationName"), parameterNames(removed));
        assertEquals(List.of("[정규화] 주소에 없는 경로 변수 파라미터를 제거함 (1개): areaCode"), notes(removed));

        String url = "http://apis.data.go.kr/1234567/TestSvc/getList/{areaCode}";
        NormalizedOperation listed = normalize(
                document(url, row("serviceKey", "인증키", "100", "1", "", ""), row("areaCode", "지역코드", "4", "0", "11", "")),
                extraction(documentOperation(url, query("serviceKey"),
                        parameter("areaCode", Location.PATH, LlmValueType.STRING, Requirement.OPTIONAL, "11", "", false))));
        JsonNode areaCode = api(listed).get("requestParameters").get(1);
        assertEquals("path", areaCode.get("in").asText());
        assertTrue(areaCode.get("required").asBoolean(), "경로 변수는 늘 필수다");
        assertEquals("areaCode 설명", areaCode.get("description").asText(), "LLM이 적은 정의를 쓴다");
    }

    @Test
    void 헤더나_본문_파라미터가_있으면_지원_범위_밖으로_건너뛰되_IR은_돌려준다() {
        NormalizedOperation normalized = normalize(standardOperation().parameters(query("serviceKey"),
                parameter("returnType", Location.HEADER, LlmValueType.STRING, Requirement.OPTIONAL, "", "", false),
                parameter("stationName", Location.BODY, LlmValueType.STRING, Requirement.OPTIONAL, "", "", false)));

        assertTrue(normalized.outOfScope());
        assertEquals("요청 파라미터 위치 미지원 (헤더: returnType / 본문: stationName): generator는 query·path 파라미터만 받는다",
                normalized.skipReason());
        assertEquals("header", api(normalized).get("requestParameters").get(1).get("in").asText());
        assertEquals("body", api(normalized).get("requestParameters").get(2).get("in").asText());
    }

    // ---- api.responseFormat ----

    @Test
    void 응답_형식은_두_형식을_다_내면_기본_형식이고_모르면_경로를_정한_예제의_형식과_노트다() {
        // standardOperation은 XML 응답 예제로 경로를 정했다
        assertFormat(ResponseFormat.XML, DefaultResponseFormat.UNKNOWN, "", "XML", List.of());
        assertFormat(ResponseFormat.JSON, DefaultResponseFormat.UNKNOWN, "", "JSON", List.of());
        assertFormat(ResponseFormat.BOTH, DefaultResponseFormat.JSON, "_type", "JSON", List.of());
        assertFormat(ResponseFormat.BOTH, DefaultResponseFormat.UNKNOWN, "returnType", "XML", List.of());
        assertFormat(ResponseFormat.BOTH, DefaultResponseFormat.UNKNOWN, "_type", "XML",
                List.of("[정규화] 포맷 파라미터 _type는 generator가 값을 채우지 않고 기본 응답 형식도 알 수 없어 "
                        + "응답 경로를 정한 XML 예제를 따라 XML로 둠 (서버 기본 형식 확인 필요)"));
        assertFormat(ResponseFormat.BOTH, DefaultResponseFormat.UNKNOWN, "", "XML",
                List.of("[정규화] 응답 형식을 고르는 파라미터가 없고 기본 응답 형식도 알 수 없어 "
                        + "응답 경로를 정한 XML 예제를 따라 XML로 둠 (서버 기본 형식 확인 필요)"));
        assertFormat(ResponseFormat.UNKNOWN, DefaultResponseFormat.UNKNOWN, "returnType", "XML",
                List.of("[정규화] 응답 형식이 적혀 있지 않아 응답 경로를 정한 XML 예제를 따라 XML로 둠"));
    }

    @Test
    void 기본_형식을_모르면_경로를_정한_JSON_예제를_따라_JSON으로_두고_예제가_없으면_XML이다() {
        // 기본 형식을 모르는데 XML로 두면 JSON 예제를 따른 경로와 형식이 어긋나 생성 DTO가 응답을 읽지 못할 수 있다
        NormalizedOperation json = normalize(standardOperation()
                .format(ResponseFormat.BOTH, DefaultResponseFormat.UNKNOWN, "type").exampleFormat(ResponseExampleFormat.JSON));
        NormalizedOperation unstated = normalize(standardOperation()
                .format(ResponseFormat.UNKNOWN, DefaultResponseFormat.UNKNOWN, "").exampleFormat(ResponseExampleFormat.JSON));
        NormalizedOperation noExample = normalize(standardOperation()
                .format(ResponseFormat.BOTH, DefaultResponseFormat.UNKNOWN, "").structure(ResponseStructure.STANDARD_ASSUMED));

        assertEquals("JSON", api(json).get("responseFormat").asText());
        assertEquals(List.of("[정규화] 포맷 파라미터 type는 generator가 값을 채우지 않고 기본 응답 형식도 알 수 없어 "
                + "응답 경로를 정한 JSON 예제를 따라 JSON으로 둠 (서버 기본 형식 확인 필요)"), notes(json));
        assertEquals("JSON", api(unstated).get("responseFormat").asText());
        assertEquals(List.of("[정규화] 응답 형식이 적혀 있지 않아 응답 경로를 정한 JSON 예제를 따라 JSON으로 둠"), notes(unstated));
        assertEquals("XML", api(noExample).get("responseFormat").asText());
        assertEquals(List.of("[정규화] 응답 형식을 고르는 파라미터가 없고 기본 응답 형식도 알 수 없어 XML로 둠 (서버 기본 형식 확인 필요)",
                "[정규화] 응답 구조를 표준 구조로 가정함"), notes(noExample));
    }

    @Test
    void 문서가_정한_형식과_다른_형식의_예제로_표준이_아닌_경로를_정했으면_경로를_확인하라고_알린다() {
        String url = "http://api.example.kr/svc/getList";
        List<Block> blocks = List.of(
                heading("상세기능정보"), table(row("상세기능명(국문)", "목록 조회"), row("Call Back URL", url)),
                heading("요청 메시지 명세"), table(HEADER, row("serviceKey", "인증키", "100", "1", "-", "인증키")),
                heading("응답 메시지 명세"), table(HEADER, row("resultCode", "결과코드", "2", "1", "00", "결과코드"),
                        row("itemName", "항목명", "50", "0", "종로", "항목 이름")),
                heading("요청/응답 메시지 예제"), table(row("응답메시지"),
                        row("{\"result\": {\"resultCode\": \"00\", \"list\": [{\"itemName\": \"종로\"}]}}")));
        // XML 응답이라고 밝혔지만 예제는 JSON뿐이라 JSON 예제의 구조로 경로를 정했다
        LlmFixtures.OperationBuilder operation = operation().title("목록 조회").callBackUrl(url).parameters(query("serviceKey"))
                .format(ResponseFormat.XML, DefaultResponseFormat.XML, "").exampleFormat(ResponseExampleFormat.JSON)
                .fields(field("result.resultCode", RESPONSE_EXAMPLE), field("result.list[].itemName", RESPONSE_EXAMPLE))
                .resultCode("resultCode", LlmFixtures.success("00", LlmSuccessCode.Source.FIELD_TABLE));

        NormalizedOperation nonStandard = normalize(blocks, extraction(operation));
        NormalizedOperation standard = normalize(standardOperation()
                .format(ResponseFormat.XML, DefaultResponseFormat.XML, "").exampleFormat(ResponseExampleFormat.JSON));

        assertEquals("XML", api(nonStandard).get("responseFormat").asText(), "문서가 정한 형식은 바꾸지 않는다");
        assertEquals(List.of("[정규화] 응답 형식은 XML인데 응답 경로는 JSON 예제를 따름 (XML 응답의 구조와 같은지 확인 필요)"),
                notes(nonStandard));
        assertEquals(List.of(), notes(standard), "표준 구조면 생성 DTO가 XML과 JSON의 두 모양을 다 읽는다");
    }

    @Test
    void 두_형식을_다_내고_생성_클라이언트가_포맷_파라미터로_고르면_형식을_고를_수_있다고_표시한다() {
        NormalizedOperation selectable = normalize(standardOperation()
                .format(ResponseFormat.BOTH, DefaultResponseFormat.XML, "returnType")
                .parameters(query("serviceKey"), query("returnType")));
        NormalizedOperation unfilled = normalize(standardOperation()
                .format(ResponseFormat.BOTH, DefaultResponseFormat.XML, "_type")
                .parameters(query("serviceKey"), query("_type")));
        NormalizedOperation single = normalize(standardOperation()
                .format(ResponseFormat.XML, DefaultResponseFormat.XML, "returnType")
                .parameters(query("serviceKey"), query("returnType")));

        assertTrue(selectable.formatSelectable());
        assertFalse(unfilled.formatSelectable(), "generator가 값을 채우지 않는 파라미터면 서버 기본 형식이 온다");
        assertFalse(single.formatSelectable(), "한 형식만 내는 API는 형식을 고를 수 없다");
    }

    // ---- api.responseFields ----

    @Test
    void 응답_필드의_타입은_소문자이고_결과코드와_결과메시지는_늘_string이며_결과코드에_결과_표시를_붙인다() {
        NormalizedOperation normalized = normalize(standardOperation().fields(
                field("response.header.resultCode", LlmValueType.INTEGER, "00", RESPONSE_EXAMPLE, false),
                field("response.header.resultMsg", LlmValueType.INTEGER, "", RESPONSE_EXAMPLE, false),
                field("response.body.totalCount", LlmValueType.INTEGER, "1", RESPONSE_EXAMPLE, false),
                field("response.body.items.item[].stationCode", LlmValueType.INTEGER, "011123", FIELD_TABLE, false),
                field("response.body.items.item[].stationName", LlmValueType.STRING, "종로구", RESPONSE_EXAMPLE, false)));

        JsonNode fields = api(normalized).get("responseFields");
        assertEquals(List.of("path", "type", "description", "isResultIndicator"), keys(fields.get(0)));
        assertEquals("string", fields.get(0).get("type").asText());
        assertTrue(fields.get(0).get("isResultIndicator").asBoolean());
        assertEquals("string", fields.get(1).get("type").asText());
        assertFalse(fields.get(1).has("isResultIndicator"), "결과메시지는 결과 표시가 아니다");
        assertEquals("integer", fields.get(2).get("type").asText());
        assertEquals("string", fields.get(3).get("type").asText(), "샘플이 앞자리 0인 숫자");
        assertEquals("response.body.items.item[].stationName", fields.get(4).get("path").asText());
        assertEquals("stationName 설명", fields.get(4).get("description").asText());
        assertEquals(List.of("[정규화] 앞자리 0이 있는 숫자 값이라 string으로 둔 항목 (1개): stationCode"), notes(normalized),
                "결과코드는 앞자리 0 노트 없이 string이다");
    }

    @Test
    void resultCode_fieldName이_가리키는_필드도_결과코드로_보고_결과코드_위치_후보_앞에_더한다() {
        List<Block> blocks = new ArrayList<>(SampleDocument.standard());
        List<List<String>> response = new ArrayList<>(((Block.Table) blocks.get(7)).rows());
        response.add(row("rtnCd", "처리결과코드", "2", "1", "0", "처리 결과 코드"));
        blocks.set(7, new Block.Table(response));

        NormalizedOperation normalized = normalize(blocks, extraction(standardOperation().resultCode("rtnCd")
                .fields(field("response.header.rtnCd", LlmValueType.INTEGER, "0", FIELD_TABLE, false))));

        JsonNode field = api(normalized).get("responseFields").get(0);
        assertEquals("string", field.get("type").asText());
        assertTrue(field.get("isResultIndicator").asBoolean());
        assertEquals(List.of("rtnCd", "response.header.resultCode", "resultCode", "RESULT_CODE",
                "cmmMsgHeader.returnReasonCode"), texts(errorSpec(normalized).get("resultCodeFieldCandidates")));

        assertEquals(texts(IrConventions.errorSpec().get("resultCodeFieldCandidates")),
                texts(errorSpec(normalize(standardOperation())).get("resultCodeFieldCandidates")),
                "기본 목록에 있는 이름(resultCode)은 더하지 않는다");
    }

    @Test
    void 응답_루트가_둘_이상이면_3단계_전까지_지원_범위_밖이다() {
        List<Block> blocks = new ArrayList<>(SampleDocument.standard());
        blocks.set(9, table(row("응답메시지"), row("{\n\"header\": {\"resultCode\": \"00\"},\n\"body\": {\"totalCount\": 1}\n}")));

        NormalizedOperation normalized = normalize(blocks, extraction(standardOperation().fields(
                field("header.resultCode", RESPONSE_EXAMPLE), field("body.totalCount", RESPONSE_EXAMPLE))));

        assertTrue(normalized.outOfScope());
        assertEquals("응답 루트가 둘 이상 (header, body): 3단계 IR 확장 전까지 generator는 루트 하나로 감싼 응답만 받는다",
                normalized.skipReason());
        assertEquals("header.resultCode", api(normalized).get("responseFields").get(0).get("path").asText());
    }

    @Test
    void 응답_구조를_표준_구조로_가정한_오퍼레이션은_노트를_남긴다() {
        NormalizedOperation normalized = normalize(standardOperation().structure(ResponseStructure.STANDARD_ASSUMED)
                .fields(field("response.header.resultCode", FIELD_TABLE)));

        assertEquals(List.of("[정규화] 응답 구조를 표준 구조로 가정함"), notes(normalized));
    }

    // ---- api.errorSpec ----

    @Test
    void 성공코드_후보가_없으면_00을_넣고_노트를_남기며_검증한_에러코드를_옮긴다() {
        NormalizedOperation normalized = normalize(standardOperation().resultCode("resultCode"));

        assertEquals("00", errorSpec(normalized).get("successResultCode").asText());
        assertEquals(List.of("[정규화] 문서에서 성공코드를 찾지 못해 00으로 둠"), notes(normalized));
        JsonNode known = errorSpec(normalized).get("knownErrorCodes");
        assertEquals(2, known.size());
        assertEquals("01", known.get(0).get("code").asText());
        assertEquals("APPLICATION ERROR", known.get(0).get("message").asText());
        assertFalse(errorSpec(normalize(standardOperation().errors())).has("knownErrorCodes"), "에러코드가 없으면 키를 두지 않는다");
    }

    @Test
    void 성공코드는_검증을_통과한_후보에서_정책대로_고르고_엇갈리면_검증_노트를_남긴다() {
        NormalizedOperation normalized = normalize(standardOperation().resultCode("resultCode",
                LlmFixtures.success("00", LlmSuccessCode.Source.FIELD_TABLE),
                LlmFixtures.success("00", LlmSuccessCode.Source.ERROR_TABLE),
                // 응답 예제에 없는 후보는 V8이 버려 고르지 않는다
                LlmFixtures.success("INFO-00", LlmSuccessCode.Source.RESPONSE_EXAMPLE)));

        assertEquals("00", errorSpec(normalized).get("successResultCode").asText());
        assertEquals(List.of(), notes(normalized));
    }

    // ---- reviewNotes, manualReviewRequired, 건너뜀 ----

    @Test
    void 검수_노트는_출처를_붙여_LLM_검증_정규화_순으로_모은다() {
        NormalizedOperation normalized = normalize(SampleDocument.standard(), extraction("", List.of("문서 전체에 대한 노트"),
                standardOperation().uncertain("확신 없는 점").unsupported("지원하지 않는 요소")
                        .addParameter(query("fakeParam"))
                        .addParameter(parameter("areaCode", Location.QUERY, LlmValueType.STRING, Requirement.UNKNOWN, "", "", false))));

        assertEquals(List.of(
                "[LLM] 확신 없는 점",
                "[LLM] 문서 전체에 대한 노트",
                "[LLM] 지원하지 않는 요소",
                "[검증] 원문에서 찾지 못한 요청 파라미터를 제거함 (1개): fakeParam",
                "[정규화] 필수 여부를 알 수 없어 선택(false)으로 둔 요청 파라미터 (1개): areaCode"), notes(normalized));
        assertTrue(normalized.ir().get("metadata").get("manualReviewRequired").asBoolean());
    }

    @Test
    void 노트가_없으면_검수가_필요하지_않다() {
        NormalizedOperation normalized = normalize(standardOperation());

        assertEquals(List.of(), notes(normalized));
        assertFalse(normalized.ir().get("metadata").get("manualReviewRequired").asBoolean());
    }

    @Test
    void 검증에서_건너뛴_오퍼레이션은_IR_없이_이유만_돌려준다() {
        NormalizedOperation dropped = normalize(standardOperation()
                .fields(field("response.body.items.item[].fakeField", RESPONSE_EXAMPLE)));

        assertNull(dropped.ir());
        assertNull(dropped.apiId());
        assertFalse(dropped.outOfScope());
        assertFalse(dropped.converted());
        assertEquals("검증을 통과한 응답 필드가 없습니다", dropped.skipReason());
        assertEquals("getMsrstnList", dropped.operationName());
        assertEquals("측정소 목록 조회", dropped.title());
    }

    @Test
    void 주소를_나눈_뒤에_건너뛴_오퍼레이션은_apiId를_함께_알린다() {
        List<NormalizedOperation> normalized = new IrNormalizer(LLM).normalize(new ExtractionValidator().validate(
                extraction(standardOperation(), standardOperation().name("").title("측정소 목록 다시 조회")),
                SampleDocument.standard()), "sample.docx", "DOCX");

        assertTrue(normalized.get(0).converted());
        NormalizedOperation duplicate = normalized.get(1);
        assertNull(duplicate.ir());
        assertEquals("MsrstnList", duplicate.apiId());
        assertTrue(duplicate.skipReason().startsWith("주소가 앞 오퍼레이션(getMsrstnList)과 같습니다"), duplicate.skipReason());
    }

    // ---- 도우미 ----

    private NormalizedOperation normalize(LlmFixtures.OperationBuilder operation) {
        return normalize(SampleDocument.standard(), extraction(operation));
    }

    private NormalizedOperation normalize(List<Block> blocks, LlmExtraction extraction) {
        return new IrNormalizer(LLM).normalize(new ExtractionValidator().validate(extraction, blocks), "sample.docx", "DOCX")
                .get(0);
    }

    private void assertFormat(ResponseFormat format, DefaultResponseFormat defaultFormat, String parameter,
                              String expected, List<String> expectedNotes) {
        NormalizedOperation normalized = normalize(standardOperation().format(format, defaultFormat, parameter));
        String label = format + "/" + defaultFormat + "/" + parameter;
        assertEquals(expected, api(normalized).get("responseFormat").asText(), label);
        assertEquals(expectedNotes, notes(normalized), label);
    }

    /** 상세기능정보, 요청 표, 응답 표, XML 응답 예제만 담은 작은 문서 */
    @SafeVarargs
    private List<Block> document(String url, List<String>... requestRows) {
        List<List<String>> request = new ArrayList<>();
        request.add(HEADER);
        request.addAll(List.of(requestRows));
        return List.of(
                heading("상세기능정보"), table(row("상세기능명(국문)", "목록 조회"), row("Call Back URL", url)),
                heading("요청 메시지 명세"), new Block.Table(request),
                heading("응답 메시지 명세"), table(HEADER, row("resultCode", "결과코드", "2", "1", "00", "결과코드"),
                        row("itemName", "항목명", "50", "0", "종로", "항목 이름")),
                heading("요청/응답 메시지 예제"), table(row("응답메시지"), row(RESPONSE)));
    }

    private LlmFixtures.OperationBuilder documentOperation(String url, LlmRequestParameter... parameters) {
        return operation().title("목록 조회").callBackUrl(url).parameters(parameters)
                .fields(field("response.header.resultCode", RESPONSE_EXAMPLE),
                        field("response.body.items.item[].itemName", RESPONSE_EXAMPLE));
    }

    private JsonNode api(NormalizedOperation normalized) {
        return normalized.ir().get("api");
    }

    private JsonNode errorSpec(NormalizedOperation normalized) {
        return api(normalized).get("errorSpec");
    }

    private List<String> notes(NormalizedOperation normalized) {
        return texts(normalized.ir().get("metadata").get("reviewNotes"));
    }

    private List<String> parameterNames(NormalizedOperation normalized) {
        List<String> names = new ArrayList<>();
        api(normalized).get("requestParameters").forEach(parameter -> names.add(parameter.get("name").asText()));
        return names;
    }

    private List<String> texts(JsonNode array) {
        List<String> texts = new ArrayList<>();
        array.forEach(node -> texts.add(node.asText()));
        return texts;
    }

    private List<String> keys(JsonNode node) {
        List<String> keys = new ArrayList<>();
        node.fieldNames().forEachRemaining(keys::add);
        return keys;
    }
}
