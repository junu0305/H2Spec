package kr.go.h2spec.parser.llm.validate;

import kr.go.h2spec.parser.Block;
import kr.go.h2spec.parser.llm.LlmFixtures;
import kr.go.h2spec.parser.llm.SampleDocument;
import kr.go.h2spec.parser.llm.schema.LlmExtraction;
import kr.go.h2spec.parser.llm.schema.LlmOperation;
import kr.go.h2spec.parser.llm.schema.LlmOperation.ResponseStructure;
import kr.go.h2spec.parser.llm.schema.LlmRequestParameter.Location;
import kr.go.h2spec.parser.llm.schema.LlmRequestParameter.Requirement;
import kr.go.h2spec.parser.llm.schema.LlmSuccessCode;
import kr.go.h2spec.parser.llm.schema.LlmValueType;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static kr.go.h2spec.parser.llm.LlmFixtures.commonQuery;
import static kr.go.h2spec.parser.llm.LlmFixtures.error;
import static kr.go.h2spec.parser.llm.LlmFixtures.extraction;
import static kr.go.h2spec.parser.llm.LlmFixtures.field;
import static kr.go.h2spec.parser.llm.LlmFixtures.operation;
import static kr.go.h2spec.parser.llm.LlmFixtures.parameter;
import static kr.go.h2spec.parser.llm.LlmFixtures.query;
import static kr.go.h2spec.parser.llm.LlmFixtures.success;
import static kr.go.h2spec.parser.llm.SampleDocument.EXAMPLE_URL;
import static kr.go.h2spec.parser.llm.SampleDocument.HEADER;
import static kr.go.h2spec.parser.llm.SampleDocument.SERVICE;
import static kr.go.h2spec.parser.llm.SampleDocument.URL;
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
 * 검증 규칙(설계 5.1의 V1~V12)을 하나씩 본다. 문서는 {@link SampleDocument}의 작은 표준 기술문서이고,
 * LLM 출력은 그 문서를 그대로 옮긴 것({@link SampleDocument#standardOperation()})에서 규칙마다 한 곳만 바꾼다.
 */
class ExtractionValidatorTest {

    private final ExtractionValidator validator = new ExtractionValidator();

    @Test
    void 원문과_맞는_LLM_출력은_그대로_통과한다() {
        ValidatedOperation operation = validate(standardOperation());

        assertFalse(operation.dropped());
        assertEquals(List.of(), operation.notes());
        assertEquals(List.of(), operation.removals());
        assertEquals("getMsrstnList", operation.operationName());
        assertEquals("측정소 목록 조회", operation.title());
        assertEquals(URL, operation.address().url());
        assertEquals(SERVICE, operation.address().parts().baseUrl());
        assertEquals("/getMsrstnList", operation.address().parts().endpoint());
        assertEquals("MsrstnList", operation.address().parts().apiId());
        assertEquals(List.of("serviceKey", "returnType", "numOfRows", "pageNo", "stationName"), names(operation));
        assertEquals(List.of("response.header.resultCode", "response.header.resultMsg", "response.body.totalCount",
                "response.body.items.item[].stationName", "response.body.items.item[].sidoName",
                "response.body.items.item[].stationCode"), paths(operation));
        assertEquals(List.of("00", "00"), operation.successCodes().stream().map(ValidatedOperation.SuccessCode::code).toList());
        assertEquals(List.of("01", "30"), operation.errorCodes().stream().map(code -> code.code()).toList());
    }

    // ---- V1 ----

    @Test
    void V1_원문에_없는_주소_후보는_버리고_노트를_남긴다() {
        String fake = SERVICE + "/getFakeList";

        ValidatedOperation operation = validate(standardOperation().tableUrl(fake));

        assertNull(operation.address().sources().tableUrl());
        assertNull(operation.address().candidates().tableUrl());
        assertEquals(List.of("[검증] 원문에서 찾지 못한 주소 후보를 버림 (tableUrl): " + fake), operation.notes());
        assertEquals(List.of("V1"), rules(operation));
        assertEquals(URL, operation.address().url(), "남은 후보로 고른다");
    }

    @Test
    void V1_찾은_주소_후보는_LLM이_옮긴_글이_아니라_찾은_칸의_원래_글에서_rule_엔진과_같은_함수로_꺼낸다() {
        // Call Back URL 칸이 칸 폭 때문에 꺾였다. LLM은 주소 안에 공백을 넣지 않고 옮긴다
        String wrapped = "http://apis.data.go.kr/B552584/\nMsrstnInfoInqireSvc/getMsrstnList";
        List<Block> blocks = withInfoTable(row("Call Back URL", wrapped));

        ValidatedOperation operation = validate(blocks, standardOperation());

        assertEquals(wrapped, operation.address().sources().callBackUrl(), "찾은 칸의 원래 글");
        assertEquals(URL, operation.address().candidates().callBackUrl(), "IrConventions.callBackUrl이 꺾인 주소를 잇는다");
        assertEquals(EXAMPLE_URL, operation.address().sources().requestExampleUrl());
        assertEquals(URL, operation.address().candidates().exampleUrl(), "요청 예제는 질의 문자열을 지운다");
        assertEquals(SERVICE, operation.address().candidates().serviceUrl());
        assertEquals(List.of(), operation.notes());
    }

    @Test
    void V1_영문_오퍼레이션명은_정보_표_이름_칸의_원래_글에_공백_없이_그대로_있어야_한다() {
        ValidatedOperation hallucinated = validate(standardOperation().name("getMsrstnInfo"));

        assertNull(hallucinated.operationName());
        assertNull(hallucinated.address().candidates().operationName());
        assertEquals(List.of("[검증] 정보 표의 이름 칸에서 찾지 못한 영문 오퍼레이션명을 버림: getMsrstnInfo"),
                hallucinated.notes());

        // 설명이 붙은 칸은 rule 엔진처럼 오퍼레이션명으로 보지 않는다
        List<Block> described = withInfoTable(row("오퍼레이션명(영문)", "getMsrstnList(측정소 목록)"), row("Call Back URL", URL));
        assertNull(validate(described, standardOperation()).operationName());
    }

    @Test
    void 주소는_V1을_통과한_후보로_rule_엔진과_같은_규칙이_고른다() {
        // Call Back URL이 없으면 마지막 경로 칸에 오퍼레이션명이 든 요청 예제를 쓴다
        ValidatedOperation fromExample = validate(standardOperation().callBackUrl(""));
        assertEquals(URL, fromExample.address().url());
        assertEquals(List.of(), fromExample.address().selectionNotes());

        // 원문에서 확인하지 못한 오퍼레이션명은 서비스 URL에 붙이지 않는다. 남은 후보가 서비스 URL뿐이면 그것을 쓰고 알린다
        ValidatedOperation serviceOnly = validate(standardOperation().callBackUrl("").requestExampleUrl("").name("getFake"));
        assertEquals(SERVICE, serviceOnly.address().url());
        assertEquals(List.of("오퍼레이션 주소가 없어 서비스 URL을 그대로 오퍼레이션 주소로 씀"),
                serviceOnly.address().selectionNotes());
    }

    @Test
    void 겹치는_주소는_문서_전체를_보고_자기_요청_예제의_주소로_바로잡는다() {
        String copied = "http://apis.data.go.kr/1234567/TestSvc/getTestList";
        String own = "http://apis.data.go.kr/1234567/TestSvc/getTestDetail";
        List<Block> blocks = List.of(
                heading("상세기능정보"), table(row("상세기능명(국문)", "목록 조회"), row("Call Back URL", copied)),
                heading("응답 메시지 명세"), table(HEADER, row("resultCode", "결과코드", "2", "1", "00", "결과코드")),
                heading("요청/응답 메시지 예제"), table(row("요청메시지"), row(copied + "?pageNo=1")),
                // 두 번째 기능은 첫 기능의 Call Back URL을 베꼈다
                heading("상세기능정보"), table(row("상세기능명(국문)", "상세 조회"), row("Call Back URL", copied)),
                heading("응답 메시지 명세"), table(HEADER, row("resultCode", "결과코드", "2", "1", "00", "결과코드")),
                heading("요청/응답 메시지 예제"), table(row("요청메시지"), row(own + "?pageNo=1")));

        List<ValidatedOperation> operations = validator.validate(extraction(
                standardAssumed("목록 조회", copied, copied + "?pageNo=1").evidence("T2", "T4", "T6"),
                standardAssumed("상세 조회", copied, own + "?pageNo=1").evidence("T8", "T10", "T12")), blocks).operations();

        assertEquals(copied, operations.get(0).address().url());
        assertEquals(List.of(), operations.get(0).address().correctionNotes());
        assertEquals(own, operations.get(1).address().url());
        assertEquals(List.of("Call Back URL이 다른 오퍼레이션과 같아 요청 예제의 주소를 씀: " + copied),
                operations.get(1).address().correctionNotes());
    }

    // ---- V2 ----

    @Test
    void V2_고른_주소가_형식에_맞지_않으면_오퍼레이션을_건너뛴다() {
        assertDroppedFor(SERVICE + "/getMsrstnList#top", "주소에 쓸 수 없는 글자(#)가 있습니다");
        assertDroppedFor(SERVICE + "\\getMsrstnList", "주소에 쓸 수 없는 글자(\\)가 있습니다");
        assertDroppedFor("http://{host}/MsrstnInfoInqireSvc/getMsrstnList", "경로 변수가 endpoint가 아니라 baseUrl에 있습니다");
        assertDroppedFor("http://apis.data.go.kr", "주소에 호스트 뒤의 경로가 없습니다");
        assertDroppedFor("http://{host}", "Call Back URL에서 오퍼레이션 경로를 찾을 수 없습니다");
    }

    @Test
    void 고를_주소_후보가_없으면_오퍼레이션을_건너뛴다() {
        ValidatedOperation operation = validate(standardOperation().callBackUrl("").requestExampleUrl("").serviceUrl(""));

        assertEquals("오퍼레이션 주소(Call Back URL 등)를 찾지 못했습니다", operation.dropReason());
        assertNull(operation.address().url());
    }

    // ---- V3, V4 ----

    @Test
    void V3_원문에_없는_요청_파라미터는_제거하고_노트를_남긴다() {
        ValidatedOperation operation = validate(standardOperation().addParameter(query("sigunguName")).addParameter(query("No")));

        assertEquals(List.of("serviceKey", "returnType", "numOfRows", "pageNo", "stationName"), names(operation));
        assertEquals(List.of("[검증] 원문에서 찾지 못한 요청 파라미터를 제거함 (2개): sigunguName, No"), operation.notes(),
                "pageNo의 No처럼 다른 이름의 일부는 원문에 있는 것으로 보지 않는다");
        assertEquals(List.of("V3", "V3"), rules(operation));
    }

    @Test
    void V3_파라미터_이름은_찾은_자리의_원래_글을_rule_엔진과_같은_이름_정리로_다듬는다() {
        List<Block> blocks = withRequestTable(
                row("pm\n25Value", "PM2.5 농도", "4", "0", "10", "PM2.5 농도"),
                row("numOfRows\n(한 페이지 결과 수)", "한 페이지 결과 수", "4", "0", "10", ""),
                row("relevant departments", "관련 부서", "100", "0", "", "관련 부서"));

        ValidatedOperation operation = validate(blocks, standardOperation()
                .parameters(query("pm25Value"), query("numOfRows"), query("relevantdepartments")));

        assertEquals(List.of("pm25Value", "numOfRows", "relevant departments"), names(operation),
                "꺾인 이름은 잇고, 소문자로 이어지는 이름은 문서가 적은 그대로 둔다");
    }

    @Test
    void V4_생성_코드에_쓸_수_없는_이름의_요청_파라미터는_제거하고_노트를_남긴다() {
        List<Block> blocks = withRequestTable(
                row("item.name", "항목", "10", "0", "", "점이 든 이름"),
                row("class", "구분", "10", "0", "", "예약어"),
                row("a'b", "따옴표", "10", "0", "", "따옴표가 든 이름"),
                row("page-no", "페이지", "4", "0", "1", "Java 이름은 pageNo"),
                row("검색어", "검색어", "10", "0", "", "한글 이름"));

        ValidatedOperation operation = validate(blocks, standardOperation()
                .parameters(query("item.name"), query("class"), query("a'b"), query("page-no"), query("검색어")));

        assertEquals(List.of("page-no", "검색어"), names(operation), "공백·한글·붙임표가 든 이름은 지금도 잘 생성된다");
        assertEquals(List.of("[검증] 생성 코드에 쓸 수 없는 이름의 요청 파라미터를 제거함 (3개): item.name, class, a'b"),
                operation.notes());
    }

    // ---- V5 ----

    @Test
    void V5_마지막_칸은_pathSource가_가리키는_곳에_있어야_한다() {
        List<Block> blocks = new ArrayList<>(SampleDocument.standard());
        blocks.add(heading("※ dataTime은 측정일시다"));

        ValidatedOperation operation = validate(blocks, standardOperation().fields(
                field("response.body.items.item[].stationName", RESPONSE_EXAMPLE),
                field("response.body.items.item[].stationCode", FIELD_TABLE),
                // stationCode는 응답 필드 표에만 있고 응답 예제의 태그로는 없다
                field("response.body.items.item[].stationCode", RESPONSE_EXAMPLE),
                // dataTime은 표가 아니라 문단에만 있다
                field("response.body.items.item[].dataTime", FIELD_TABLE)));

        assertEquals(List.of("response.body.items.item[].stationName", "response.body.items.item[].stationCode"),
                paths(operation));
        assertEquals(List.of(
                "[검증] 마지막 칸을 응답 예제의 키·태그에서 찾지 못한 응답 필드를 제거함 (1개): response.body.items.item[].stationCode",
                "[검증] 마지막 칸을 응답 필드 표에서 찾지 못한 응답 필드를 제거함 (1개): response.body.items.item[].dataTime"),
                operation.notes());
    }

    @Test
    void V5_응답_예제를_따른_경로의_컨테이너_칸은_예제의_키나_태그로_있어야_한다() {
        ValidatedOperation operation = validate(standardOperation().fields(
                field("response.body.items.item[].stationName", RESPONSE_EXAMPLE),
                field("response.body.list[].stationName", RESPONSE_EXAMPLE)));

        assertEquals(List.of("response.body.items.item[].stationName"), paths(operation));
        assertEquals(List.of("[검증] 컨테이너 칸을 응답 예제의 키·태그에서 찾지 못한 응답 필드를 제거함 (1개): "
                + "response.body.list[].stationName"), operation.notes());
    }

    @Test
    void V5_표에_있는_필드는_응답_예제의_구조를_따른_경로로_통과한다() {
        // stationCode는 응답 예제에 없고 표에만 있다. 마지막 칸은 표에서, 컨테이너 칸은 예제에서 찾는다
        ValidatedOperation operation = validate(standardOperation().fields(
                field("response.body.items.item[].stationCode", FIELD_TABLE)));

        assertEquals(List.of("response.body.items.item[].stationCode"), paths(operation));
        assertEquals(List.of(), operation.notes());
    }

    @Test
    void V5_JSON의_items_배열을_표준_경로로_옮긴_items_item의_item은_예제에_없어도_된다() {
        List<Block> blocks = withExample("{\n\"response\": {\n\"header\": {\"resultCode\": \"00\"},\n"
                + "\"body\": {\"items\": [{\"stationName\": \"종로구\"}]}\n}\n}");

        ValidatedOperation operation = validate(blocks, standardOperation().fields(
                field("response.header.resultCode", RESPONSE_EXAMPLE),
                field("response.body.items.item[].stationName", RESPONSE_EXAMPLE),
                field("response.body.items.entry[].stationName", RESPONSE_EXAMPLE)));

        assertEquals(List.of("response.header.resultCode", "response.body.items.item[].stationName"), paths(operation),
                "items 다음의 item만 예외다");
    }

    @Test
    void V5_표의_들여쓰기를_따른_경로의_컨테이너_칸은_표의_칸에_있어야_한다() {
        List<Block> blocks = List.of(
                heading("상세기능정보"), table(row("상세기능명(국문)", "측정소 목록 조회"), row("Call Back URL", URL)),
                heading("응답 메시지 명세"),
                table(HEADER, row("items", "목록", "-", "0..n", "-", "목록"), row("└ stationName", "측정소명", "30", "1", "종로구", "")));

        ValidatedOperation operation = validate(blocks, operation().title("측정소 목록 조회").callBackUrl(URL)
                .structure(ResponseStructure.TABLE).fields(
                        field("items[].stationName", FIELD_TABLE),
                        field("list[].stationName", FIELD_TABLE)));

        assertEquals(List.of("items[].stationName"), paths(operation), "트리 표지를 뗀 이름으로 찾는다");
        assertEquals(List.of("[검증] 컨테이너 칸을 표에서 찾지 못한 응답 필드를 제거함 (1개): list[].stationName"),
                operation.notes());
    }

    @Test
    void V5_표준_구조를_가정한_경로는_표준_골격_그대로여야_한다() {
        ValidatedOperation operation = validate(standardOperation().structure(ResponseStructure.STANDARD_ASSUMED).fields(
                field("response.header.resultCode", FIELD_TABLE),
                field("response.body.totalCount", FIELD_TABLE),
                field("response.body.items.item[].stationName", FIELD_TABLE),
                field("response.data.stationName", FIELD_TABLE),
                field("stationName", FIELD_TABLE),
                field("response.body.items.item.sidoName", FIELD_TABLE)));

        assertEquals(List.of("response.header.resultCode", "response.body.totalCount",
                "response.body.items.item[].stationName"), paths(operation));
        assertEquals(List.of("[검증] 표준 구조로 가정했지만 표준 골격이 아닌 응답 경로를 제거함 (3개): "
                + "response.data.stationName, stationName, response.body.items.item.sidoName"), operation.notes(),
                "원문 응답 예제의 루트가 response라 루트 노트는 없다");
    }

    @Test
    void V5_표준_구조를_가정했는데_원문_응답_예제의_루트가_response가_아니면_노트를_남긴다() {
        List<Block> blocks = withExample("{\n\"header\": {\"resultCode\": \"00\"},\n\"body\": {\"totalCount\": 1}\n}");

        ValidatedOperation operation = validate(blocks, standardOperation().structure(ResponseStructure.STANDARD_ASSUMED)
                .fields(field("response.header.resultCode", FIELD_TABLE), field("response.body.totalCount", FIELD_TABLE)));

        assertEquals(2, operation.responseFields().size());
        assertEquals(List.of("[검증] 응답 구조를 표준 구조로 가정했지만 원문 응답 예제의 루트가 response가 아님: header"),
                operation.notes());
    }

    // ---- V6, V7 ----

    @Test
    void V6_경로의_각_칸은_이름_규칙을_지키고_대괄호는_칸_끝에만_온다() {
        ValidatedOperation operation = validate(standardOperation().fields(
                field("response.body.it[]ems.item[].stationName", RESPONSE_EXAMPLE),
                field("response.body.items.item[].class", FIELD_TABLE),
                field("response..stationName", RESPONSE_EXAMPLE),
                field("response.body.items.item[].stationName", RESPONSE_EXAMPLE)));

        assertEquals(List.of("response.body.items.item[].stationName"), paths(operation));
        assertEquals(List.of("[검증] 이름 규칙에 맞지 않는 응답 경로를 제거함 (3개): response.body.it[]ems.item[].stationName, "
                + "response.body.items.item[].class, response..stationName"), operation.notes());
    }

    @Test
    void V7_경로_트리가_충돌하는_필드는_뒤의_것을_제거한다() {
        ValidatedOperation operation = validate(standardOperation().fields(
                field("response.body.items", RESPONSE_EXAMPLE),
                field("response.body.items.item[].stationName", RESPONSE_EXAMPLE),
                field("response.header.resultCode", RESPONSE_EXAMPLE),
                field("response.header[].resultMsg", RESPONSE_EXAMPLE)));

        assertEquals(List.of("response.body.items", "response.header.resultCode"), paths(operation));
        assertEquals(List.of(
                "[검증] 앞 필드와 경로 트리가 충돌하는(값 필드이면서 컨테이너) 응답 필드를 제거함 (1개): "
                        + "response.body.items.item[].stationName",
                "[검증] 앞 필드와 [] 표기가 다른 응답 필드를 제거함 (1개): response.header[].resultMsg"), operation.notes());
    }

    // ---- V8, V9, V10 ----

    @Test
    void V8_성공코드_후보는_출처가_가리키는_자리에_있고_코드_꼴이어야_하며_버려도_노트를_남기지_않는다() {
        ValidatedOperation operation = validate(standardOperation().resultCode("resultCode",
                success("00", LlmSuccessCode.Source.FIELD_TABLE),
                // 응답 예제의 결과코드 값은 00이다
                success("0", LlmSuccessCode.Source.RESPONSE_EXAMPLE),
                success("00", LlmSuccessCode.Source.ERROR_TABLE),
                // 에러코드 표의 01 행에는 정상·NORMAL이 없다
                success("01", LlmSuccessCode.Source.ERROR_TABLE),
                success("INFO-00", LlmSuccessCode.Source.FIELD_TABLE),
                success("00 OK", LlmSuccessCode.Source.RESPONSE_EXAMPLE)));

        assertEquals(List.of("00:FIELD_TABLE", "00:ERROR_TABLE"), operation.successCodes().stream()
                .map(code -> code.code() + ":" + code.source()).toList());
        assertEquals(List.of(), operation.notes());
        assertEquals(List.of("V8", "V8", "V8", "V8"), rules(operation));
        assertTrue(operation.successCodes().get(0).position() < operation.successCodes().get(1).position(),
                "찾은 칸의 문서 순서를 담는다");
    }

    @Test
    void V8_응답_예제의_결과코드는_JSON_값과_XML_요소_모두에서_찾는다() {
        List<Block> blocks = withExample("{\"response\": {\"header\": {\"resultCode\": 0, “resultMsg”: “OK”}}}");

        ValidatedOperation operation = validate(blocks, standardOperation()
                .fields(field("response.header.resultCode", RESPONSE_EXAMPLE))
                .resultCode("", success("0", LlmSuccessCode.Source.RESPONSE_EXAMPLE),
                        success("OK", LlmSuccessCode.Source.RESPONSE_EXAMPLE)));

        assertEquals(List.of("0"), operation.successCodes().stream().map(ValidatedOperation.SuccessCode::code).toList(),
                "결과메시지의 값은 성공코드 후보가 아니다");
    }

    @Test
    void V9_원문에_없는_에러코드는_제거하고_노트를_남기지_않는다() {
        ValidatedOperation operation = validate(standardOperation()
                .errors(error("01", "APPLICATION ERROR"), error("99", "UNKNOWN ERROR"), error("ERR", "앞부분")));

        assertEquals(List.of("01"), operation.errorCodes().stream().map(code -> code.code()).toList());
        assertEquals(List.of(), operation.notes());
        assertEquals(List.of("V9", "V9"), rules(operation));
    }

    @Test
    void V10_응답_필드가_하나도_남지_않으면_오퍼레이션을_건너뛴다() {
        ValidatedOperation operation = validate(standardOperation()
                .fields(field("response.body.items.item[].fakeField", RESPONSE_EXAMPLE)));

        assertTrue(operation.dropped());
        assertEquals("검증을 통과한 응답 필드가 없습니다", operation.dropReason());
    }

    // ---- V11 ----

    @Test
    void V11_바로잡은_뒤에도_주소가_같은_오퍼레이션은_뒤의_것을_건너뛰고_노트를_남긴다() {
        List<ValidatedOperation> operations = validator.validate(extraction(
                standardOperation(), standardOperation().name("").title("측정소 목록 다시 조회")),
                SampleDocument.standard()).operations();

        assertFalse(operations.get(0).dropped());
        assertEquals(List.of("[검증] 주소가 같은 뒤 오퍼레이션을 건너뜀 (1개): 측정소 목록 다시 조회"), operations.get(0).notes());
        assertEquals("주소가 앞 오퍼레이션(getMsrstnList)과 같습니다: " + URL, operations.get(1).dropReason());
    }

    @Test
    void V11_주소가_달라도_apiId가_같으면_뒤의_것을_건너뛴다() {
        String v1 = "http://apis.data.go.kr/1234567/TestSvc/v1/getList";
        String v2 = "http://apis.data.go.kr/1234567/TestSvc/v2/getList";
        List<Block> blocks = List.of(
                heading("상세기능정보"), table(row("상세기능명(국문)", "목록 조회"), row("Call Back URL", v1)),
                heading("응답 메시지 명세"), table(HEADER, row("resultCode", "결과코드", "2", "1", "00", "결과코드")),
                heading("상세기능정보"), table(row("상세기능명(국문)", "새 목록 조회"), row("Call Back URL", v2)),
                heading("응답 메시지 명세"), table(HEADER, row("resultCode", "결과코드", "2", "1", "00", "결과코드")));

        List<ValidatedOperation> operations = validator.validate(extraction(
                standardAssumed("목록 조회", v1, ""), standardAssumed("새 목록 조회", v2, "")), blocks).operations();

        assertEquals("apiId가 앞 오퍼레이션(목록 조회)과 같습니다: List", operations.get(1).dropReason());
        assertEquals(List.of("[검증] apiId가 같은 뒤 오퍼레이션을 건너뜀 (1개): 새 목록 조회 (" + v2 + ")"),
                operations.get(0).notes());
    }

    // ---- V12 ----

    @Test
    void V12_공통_구간과_겹치면_오퍼레이션_것을_오퍼레이션_것끼리는_앞의_것을_남기고_노트는_남기지_않는다() {
        ValidatedOperation operation = validate(standardOperation()
                .parameters(
                        commonQuery("numOfRows", "공통 구간의 설명"),
                        parameter("serviceKey", Location.QUERY, LlmValueType.STRING, Requirement.REQUIRED, "", "", false),
                        parameter("numOfRows", Location.QUERY, LlmValueType.INTEGER, Requirement.OPTIONAL, "10", "", false),
                        parameter("pageNo", Location.QUERY, LlmValueType.INTEGER, Requirement.OPTIONAL, "1", "", false),
                        parameter("pageNo", Location.QUERY, LlmValueType.INTEGER, Requirement.OPTIONAL, "2", "", false))
                .fields(
                        field("response.header.resultCode", LlmValueType.STRING, "", RESPONSE_EXAMPLE, true),
                        field("response.header.resultCode", LlmValueType.STRING, "00", RESPONSE_EXAMPLE, false)));

        assertEquals(List.of("serviceKey", "numOfRows", "pageNo"), names(operation));
        assertEquals("numOfRows 설명", operation.requestParameters().get(1).source().description(), "오퍼레이션 것을 남긴다");
        assertEquals("1", operation.requestParameters().get(2).source().example(), "앞의 것을 남긴다");
        assertEquals(1, operation.responseFields().size());
        assertFalse(operation.responseFields().get(0).source().common());
        assertEquals(List.of(), operation.notes());
        assertEquals(List.of("V12", "V12", "V12"), rules(operation));
    }

    @Test
    void V12_Java_이름으로_바꾸면_겹치는_파라미터와_같은_자리의_필드는_앞의_것을_남기고_노트를_남긴다() {
        List<Block> blocks = new ArrayList<>(withRequestTable(
                row("page_no", "페이지", "4", "0", "1", "페이지 번호"), row("pageNo", "페이지", "4", "0", "1", "페이지 번호")));
        blocks.set(7, table(HEADER, row("station_name", "측정소명", "30", "1", "종로구", ""),
                row("stationName", "측정소명", "30", "1", "종로구", "")));

        ValidatedOperation operation = validate(blocks, standardOperation()
                .parameters(query("page_no"), query("pageNo"))
                .fields(
                        field("response.body.items.item[].station_name", FIELD_TABLE),
                        field("response.body.items.item[].stationName", FIELD_TABLE),
                        field("response.body.stationName", FIELD_TABLE)));

        assertEquals(List.of("page_no"), names(operation));
        assertEquals(List.of("response.body.items.item[].station_name", "response.body.stationName"), paths(operation),
                "부모가 다르면 다른 DTO 클래스의 필드라 겹치지 않는다");
        assertEquals(List.of(
                "[검증] Java 이름이 앞 파라미터와 겹치는 요청 파라미터를 제거함 (1개): pageNo(page_no와 겹침)",
                "[검증] Java 이름이 같은 자리의 앞 필드와 겹치는 응답 필드를 제거함 (1개): "
                        + "response.body.items.item[].stationName(station_name와 겹침)"), operation.notes());
    }

    @Test
    void 회사_응답의_null은_빈_값으로_보고_예외_없이_건너뛴다() {
        LlmOperation empty = new LlmOperation(null, null, null, null, null, null, null, null, null, null, null, null,
                null, null, null, null, null, null);

        ValidatedOperation operation = validator.validate(new LlmExtraction(null, null, List.of(empty), null),
                SampleDocument.standard()).operations().get(0);

        assertTrue(operation.dropped());
        assertEquals("", operation.title());
    }

    // ---- 도우미 ----

    private ValidatedOperation validate(LlmFixtures.OperationBuilder operation) {
        return validate(SampleDocument.standard(), operation);
    }

    private ValidatedOperation validate(List<Block> blocks, LlmFixtures.OperationBuilder operation) {
        return validator.validate(extraction(operation), blocks).operations().get(0);
    }

    private void assertDroppedFor(String callBackUrl, String reason) {
        List<Block> blocks = withInfoTable(row("Call Back URL", callBackUrl));

        ValidatedOperation operation = validate(blocks,
                standardOperation().callBackUrl(callBackUrl).requestExampleUrl("").serviceUrl(""));

        assertTrue(operation.dropped(), callBackUrl);
        assertTrue(operation.dropReason().contains(reason), operation.dropReason());
    }

    /** 표준 구조를 가정하고 결과코드 필드 하나만 적은 오퍼레이션 */
    private LlmFixtures.OperationBuilder standardAssumed(String title, String callBackUrl, String exampleUrl) {
        return operation().title(title).callBackUrl(callBackUrl).requestExampleUrl(exampleUrl)
                .structure(ResponseStructure.STANDARD_ASSUMED)
                .fields(field("response.header.resultCode", FIELD_TABLE));
    }

    @SafeVarargs
    private List<Block> withInfoTable(List<String>... rows) {
        List<Block> blocks = new ArrayList<>(SampleDocument.standard());
        List<List<String>> info = new ArrayList<>(List.of(row("상세기능명(국문)", "측정소 목록 조회"),
                row("오퍼레이션명(영문)", "getMsrstnList")));
        for (List<String> row : rows) {
            info.removeIf(existing -> existing.get(0).equals(row.get(0)));
            info.add(row);
        }
        blocks.set(3, new Block.Table(info));
        return blocks;
    }

    @SafeVarargs
    private List<Block> withRequestTable(List<String>... rows) {
        List<Block> blocks = new ArrayList<>(SampleDocument.standard());
        List<List<String>> request = new ArrayList<>(((Block.Table) blocks.get(5)).rows());
        request.addAll(List.of(rows));
        blocks.set(5, new Block.Table(request));
        return blocks;
    }

    private List<Block> withExample(String responseExample) {
        List<Block> blocks = new ArrayList<>(SampleDocument.standard());
        blocks.set(9, table(row("요청메시지"), row(EXAMPLE_URL), row("응답메시지"), row(responseExample)));
        return blocks;
    }

    private List<String> names(ValidatedOperation operation) {
        return operation.requestParameters().stream().map(ValidatedOperation.Parameter::name).toList();
    }

    private List<String> paths(ValidatedOperation operation) {
        return operation.responseFields().stream().map(ValidatedOperation.Field::path).toList();
    }

    private List<String> rules(ValidatedOperation operation) {
        return operation.removals().stream().map(ValidatedOperation.Removal::rule).toList();
    }
}
