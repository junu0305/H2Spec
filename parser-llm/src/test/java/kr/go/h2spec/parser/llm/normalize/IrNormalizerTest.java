package kr.go.h2spec.parser.llm.normalize;

import com.fasterxml.jackson.databind.JsonNode;
import kr.go.h2spec.generator.ir.GeneratorHints;
import kr.go.h2spec.generator.ir.IrLoader;
import kr.go.h2spec.parser.Block;
import kr.go.h2spec.parser.IrConventions;
import kr.go.h2spec.parser.llm.LlmFixtures;
import kr.go.h2spec.parser.llm.SampleDocument;
import kr.go.h2spec.parser.llm.schema.LlmAuth;
import kr.go.h2spec.parser.llm.schema.LlmErrorCode;
import kr.go.h2spec.parser.llm.schema.LlmExtraction;
import kr.go.h2spec.parser.llm.schema.LlmOperation.DefaultResponseFormat;
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
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 정규화 규칙을 IR 칸마다 본다. 문서는 {@link SampleDocument}의 작은 표준 기술문서와, 주소·인증키가 다른 작은 문서다.
 * 정규화는 검증 결과를 받으므로 테스트도 검증을 거친 결과를 정규화한다.
 */
class IrNormalizerTest {

    private static final LlmMetadata LLM = new LlmMetadata("claude", "claude-opus-5-5", "medium", "extraction-v4", "3");
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
                "manualReviewRequired", "reviewNotes"), keys(metadata), "rule 엔진의 metadata에서 engine 바로 뒤에 llm을 더한 자리");
        assertEquals("sample.docx", metadata.get("sourceFile").asText());
        assertEquals("DOCX", metadata.get("sourceFormat").asText());
        assertEquals("llm", metadata.get("engine").asText());
        assertEquals(LLM.toJson(), metadata.get("llm"));
        assertEquals(List.of("provider", "model", "effort", "promptVersion", "schemaVersion"), keys(metadata.get("llm")));
        assertFalse(metadata.toString().toLowerCase().contains("token"), "토큰 수는 실행마다 달라 IR에 넣지 않는다");
        assertFalse(metadata.toString().toLowerCase().contains("cache"));
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
    void 헤더_키와_인증_없음은_지원_범위_밖이다() {
        NormalizedOperation none = normalize(document(ORGANIZATION_URL), extraction(documentOperation(ORGANIZATION_URL)
                .auth(LlmAuth.Kind.NONE, "")));
        assertTrue(none.skipReason().startsWith("인증 방식 미지원 (인증키 없음)"), none.skipReason());

        NormalizedOperation header = normalize(document(ORGANIZATION_URL, row("X-Api-Key", "인증키", "100", "1", "", "")),
                extraction(documentOperation(ORGANIZATION_URL,
                        parameter("X-Api-Key", Location.HEADER, LlmValueType.STRING, Requirement.REQUIRED, "", "", false))
                        .auth(LlmAuth.Kind.HEADER_KEY, "X-Api-Key")));
        assertEquals("요청 파라미터 위치 미지원 (헤더: X-Api-Key): generator는 query·path 파라미터만 받는다; "
                + "인증 방식 미지원 (헤더의 X-Api-Key): generator는 이름이 serviceKey인 쿼리 파라미터만 인증키로 받는다",
                header.skipReason(), "이유가 여럿이면 모두 적는다");
    }

    // ---- api.requestParameters ----

    @Test
    void 주소의_경로_변수가_되는_파라미터는_필수_여부를_몰라도_필수이고_선택으로_뒀다는_노트를_남기지_않는다() {
        String url = "https://openapi.example.kr/svc/getList/{itemId}";
        NormalizedOperation normalized = normalize(
                document(url, row("serviceKey", "인증키", "100", "1", "-", "인증키"), row("itemId", "항목 ID", "10", "0", "", "항목 ID")),
                extraction(documentOperation(url, query("serviceKey"),
                        parameter("itemId", Location.QUERY, LlmValueType.STRING, Requirement.UNKNOWN, "", "", false))));

        JsonNode itemId = null;
        for (JsonNode parameter : api(normalized).get("requestParameters")) {
            if ("itemId".equals(parameter.get("name").asText())) {
                itemId = parameter;
            }
        }
        assertEquals("path", itemId.get("in").asText());
        assertTrue(itemId.get("required").asBoolean());
        assertTrue(notes(normalized).stream().noneMatch(note -> note.contains("필수 여부를 알 수 없어")), notes(normalized).toString());
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

    // ---- api.responseFormat ----

    @Test
    void 응답_형식은_두_형식을_다_내면_기본_형식이고_모르면_경로를_정한_예제의_형식과_노트다() {
        // standardOperation은 XML 응답 예제로 경로를 정했다
        assertFormat(ResponseFormat.XML, DefaultResponseFormat.UNKNOWN, "", "XML", List.of());
        assertFormat(ResponseFormat.JSON, DefaultResponseFormat.UNKNOWN, "", "JSON", List.of());
        assertFormat(ResponseFormat.BOTH, DefaultResponseFormat.JSON, "_type", "JSON", List.of());
        assertFormat(ResponseFormat.BOTH, DefaultResponseFormat.UNKNOWN, "returnType", "XML", List.of());
        assertFormat(ResponseFormat.BOTH, DefaultResponseFormat.UNKNOWN, "_type", "XML",
                List.of("[정규화] 포맷 파라미터 _type가 요청 파라미터에 없고 기본 응답 형식도 알 수 없어 "
                        + "응답 경로를 정한 XML 예제를 따라 XML로 둠 (서버 기본 형식 확인 필요)"));
        // LLM이 포맷 파라미터를 밝히지 않아도 generator가 아는 returnType이 있으면 생성 클라이언트가 형식을 채워 보낸다
        assertFormat(ResponseFormat.BOTH, DefaultResponseFormat.UNKNOWN, "", "XML", List.of());
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
                .format(ResponseFormat.BOTH, DefaultResponseFormat.UNKNOWN, "").structure(ResponseStructure.STANDARD_ASSUMED)
                .parameters(query("serviceKey")));

        assertEquals("JSON", api(json).get("responseFormat").asText());
        assertEquals(List.of("[정규화] 포맷 파라미터 type가 요청 파라미터에 없고 기본 응답 형식도 알 수 없어 "
                + "응답 경로를 정한 JSON 예제를 따라 JSON으로 둠 (서버 기본 형식 확인 필요)"), notes(json));
        assertEquals("JSON", api(unstated).get("responseFormat").asText());
        assertEquals(List.of("[정규화] 응답 형식이 적혀 있지 않아 응답 경로를 정한 JSON 예제를 따라 JSON으로 둠"), notes(unstated));
        assertEquals("XML", api(noExample).get("responseFormat").asText());
        assertEquals(List.of("[정규화] 응답 형식을 고르는 파라미터가 없고 기본 응답 형식도 알 수 없어 XML로 둠 (서버 기본 형식 확인 필요)",
                "[정규화] 응답 구조를 표준 구조로 가정함"), notes(noExample));
    }

    @Test
    void 기본_형식을_모르면_형식_노트는_생성_클라이언트가_실제로_형식을_채우는지로_정하고_형식_선택_표시와_같다() {
        // 문서가 밝힌 포맷 파라미터가 IR에 없으면 다른 파라미터가 있어도 형식을 고르지 못한다
        NormalizedOperation missing = normalize(standardOperation()
                .format(ResponseFormat.BOTH, DefaultResponseFormat.UNKNOWN, "returnType").parameters(query("serviceKey")));
        NormalizedOperation filled = normalize(standardOperation()
                .format(ResponseFormat.BOTH, DefaultResponseFormat.UNKNOWN, ""));

        assertEquals(List.of("[정규화] 포맷 파라미터 returnType가 요청 파라미터에 없고 기본 응답 형식도 알 수 없어 "
                + "응답 경로를 정한 XML 예제를 따라 XML로 둠 (서버 기본 형식 확인 필요)"), notes(missing));
        assertFalse(missing.formatSelectable());
        assertEquals(List.of(), notes(filled), "generator가 아는 returnType을 채워 보내므로 서버 기본 형식을 몰라도 된다");
        assertTrue(filled.formatSelectable());
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
                .parameters(query("serviceKey")));
        NormalizedOperation single = normalize(standardOperation()
                .format(ResponseFormat.XML, DefaultResponseFormat.XML, "returnType")
                .parameters(query("serviceKey"), query("returnType")));

        assertTrue(selectable.formatSelectable());
        assertFalse(unfilled.formatSelectable(), "요청 파라미터에 없는 포맷 파라미터는 채울 수 없어 서버 기본 형식이 온다");
        assertFalse(single.formatSelectable(), "한 형식만 내는 API는 형식을 고를 수 없다");
    }

    @Test
    void generator가_모르는_포맷_파라미터는_generatorHints로_알리고_값의_대소문자는_예제를_따른다() throws Exception {
        LlmRequestParameter upper = parameter("apiType", Location.QUERY, LlmValueType.STRING, Requirement.OPTIONAL,
                "XML", "", false);
        NormalizedOperation hinted = normalize(
                document(ORGANIZATION_URL, row("serviceKey", "인증키", "100", "1", "-", "인증키"),
                        row("apiType", "응답형식", "4", "0", "XML", "XML 또는 JSON")),
                extraction(documentOperation(ORGANIZATION_URL, query("serviceKey"), upper)
                        .format(ResponseFormat.BOTH, DefaultResponseFormat.UNKNOWN, "apiType")));
        NormalizedOperation known = normalize(standardOperation()
                .format(ResponseFormat.BOTH, DefaultResponseFormat.XML, "returnType")
                .parameters(query("serviceKey"), query("returnType")));

        assertEquals("apiType", hinted.ir().at("/generatorHints/responseFormatParameter/name").asText());
        assertEquals("XML", hinted.ir().at("/generatorHints/responseFormatParameter/value").asText());
        assertTrue(hinted.formatSelectable(), "값을 채우므로 두 형식 중 고를 수 있다");
        assertTrue(notes(hinted).stream().noneMatch(note -> note.contains("기본 응답 형식")),
                "생성 클라이언트가 형식을 골라 보내므로 서버 기본 형식을 몰라도 된다: " + notes(hinted));
        assertTrue(known.ir().at("/generatorHints/responseFormatParameter").isMissingNode(),
                "generator가 아는 이름은 힌트 없이 채운다");

        Path file = tempDir.resolve("hinted.json");
        Files.writeString(file, hinted.ir().toPrettyString());
        assertEquals(new GeneratorHints.ResponseFormatParameter("apiType", "XML"),
                new IrLoader().load(file).generatorHints().responseFormatParameter());
    }

    // ---- api.errorSpec ----

    @Test
    void 에러코드는_오퍼레이션의_표_뒤에_원문에_있는_문서_공통_표를_붙이고_같은_코드는_앞의_것만_남긴다() {
        LlmFixtures.OperationBuilder operation = standardOperation()
                .errors(new LlmErrorCode("30", "SERVICE KEY IS NOT REGISTERED ERROR"));
        List<LlmErrorCode> shared = List.of(new LlmErrorCode("01", "APPLICATION ERROR"),
                new LlmErrorCode("30", "등록되지 않은 서비스키"), new LlmErrorCode("99", "원문에 없는 코드"));

        NormalizedOperation normalized = normalize(SampleDocument.standard(),
                LlmFixtures.extraction("한국환경공단", shared, List.of(), operation));

        List<String> known = new ArrayList<>();
        errorSpec(normalized).get("knownErrorCodes")
                .forEach(error -> known.add(error.get("code").asText() + " " + error.get("message").asText()));
        assertEquals(List.of("30 SERVICE KEY IS NOT REGISTERED ERROR", "01 APPLICATION ERROR"), known,
                "99는 원문에 없어 검증(V9)에서 버린다");
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
    void 응답_루트가_둘_이상이면_지원_범위_밖이다() {
        List<Block> blocks = new ArrayList<>(SampleDocument.standard());
        blocks.set(9, table(row("응답메시지"), row("{\n\"header\": {\"resultCode\": \"00\"},\n\"body\": {\"totalCount\": 1}\n}")));

        NormalizedOperation normalized = normalize(blocks, extraction(standardOperation().fields(
                field("header.resultCode", RESPONSE_EXAMPLE), field("body.totalCount", RESPONSE_EXAMPLE))));

        assertTrue(normalized.outOfScope());
        assertEquals("응답 루트가 둘 이상 (header, body): generator는 루트 하나로 감싼 응답만 받는다",
                normalized.skipReason());
        assertEquals("header.resultCode", api(normalized).get("responseFields").get(0).get("path").asText());
    }

    // ---- metadata.reviewNotes ----

    @Test
    void 코드가_따로_알리는_것을_LLM이_다시_적은_노트는_거르고_나머지는_남긴다() {
        String url = "http://api.example.kr/svc/getList";
        List<Block> blocks = List.of(
                heading("상세기능정보"), table(row("상세기능명(국문)", "목록 조회"), row("Call Back URL", url)),
                heading("요청 메시지 명세"), table(HEADER, row("serviceKey", "인증키", "100", "1", "-", "인증키")),
                heading("응답 메시지 명세"), table(HEADER, row("resultCode", "결과코드", "2", "1", "00", "결과코드"),
                        row("itemName", "항목명", "50", "0", "종로", "항목 이름")),
                heading("요청/응답 메시지 예제"), table(row("응답메시지"), row("<response><header><resultCode>0</resultCode>"
                        + "</header><body><items><item><itemName>종로</itemName></item></items></body></response>")));
        // 표의 샘플은 00, 예제는 0이라 성공코드 후보가 엇갈려 [검증] 노트가 붙는다
        LlmFixtures.OperationBuilder operation = operation().title("목록 조회").callBackUrl(url).parameters(query("serviceKey"))
                .format(ResponseFormat.BOTH, DefaultResponseFormat.UNKNOWN, "").exampleFormat(ResponseExampleFormat.XML)
                .structure(ResponseStructure.EXAMPLE)
                .fields(field("response.header.resultCode", RESPONSE_EXAMPLE),
                        field("response.body.items.item[].itemName", RESPONSE_EXAMPLE))
                .resultCode("resultCode", LlmFixtures.success("00", LlmSuccessCode.Source.FIELD_TABLE),
                        LlmFixtures.success("0", LlmSuccessCode.Source.RESPONSE_EXAMPLE))
                .uncertain("응답 필드 표의 resultCode 샘플은 00이고 응답 예제는 0으로 서로 다르다.",
                        "기본 응답 형식은 문서에 밝혀져 있지 않다.",
                        "결과코드 필드 이름이 표와 예제에서 다르게 적혀 있다.",
                        "예제 구조가 표준 구조와 같아 표준 경로로 적었다.");
        NormalizedOperation assumed = normalize(standardOperation().structure(ResponseStructure.STANDARD_ASSUMED)
                .uncertain("응답 예제가 없어 표준 구조를 가정했다.", "다른 오퍼레이션의 표를 베낀 것으로 보인다."));

        List<String> notes = notes(normalize(blocks, extraction(operation)));

        assertEquals(List.of("[LLM] 결과코드 필드 이름이 표와 예제에서 다르게 적혀 있다.",
                "[LLM] 예제 구조가 표준 구조와 같아 표준 경로로 적었다."),
                notes.stream().filter(note -> note.startsWith("[LLM]")).toList(),
                "값을 들먹이지 않은 결과코드 이야기와, 예제로 구조를 정한 오퍼레이션의 표준 구조 이야기는 남긴다");
        assertTrue(notes.stream().anyMatch(note -> note.startsWith("[검증] 성공코드 후보: ")), notes.toString());
        assertEquals(List.of("[LLM] 다른 오퍼레이션의 표를 베낀 것으로 보인다."),
                notes(assumed).stream().filter(note -> note.startsWith("[LLM]")).toList());
        assertTrue(notes(assumed).contains("[정규화] 응답 구조를 표준 구조로 가정함"), notes(assumed).toString());
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
