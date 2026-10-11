package kr.go.h2spec.eval.proxy;

import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import kr.go.h2spec.eval.EngineOperation;
import kr.go.h2spec.eval.proxy.SuccessCodes.Candidate;
import kr.go.h2spec.eval.proxy.SuccessCodes.Source;
import kr.go.h2spec.parser.Block;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 응답 예제 찾기, 성공코드 후보, 인증 흔적의 경계를 블록을 직접 만들어 확인한다.
 */
class SignalsTest {

    private static final List<String> FIELD_HEADER =
            List.of("항목명(영문)", "항목명(국문)", "항목크기", "항목구분", "샘플데이터", "항목설명");

    @Test
    void 요청_예제는_빼고_응답_예제와_에러_응답_형식을_가른다() {
        List<Block> blocks = List.of(
                table(row("요청메시지"), row("{\"keyword\": \"서울\"}"), row("응답메시지"),
                        row("{\"response\": {\"header\": {\"resultCode\": \"00\"}}}")),
                new Block.Heading("※ 오류 메시지는 아래와 같다"),
                new Block.Heading("<response>"),
                new Block.Heading("<header><resultCode>ERROR-310</resultCode></header>"),
                new Block.Heading("</response>"));

        List<ResponseExamples.ResponseExample> examples = ResponseExamples.find(blocks);

        assertEquals(2, examples.size(), "요청메시지 다음의 JSON은 요청 본문이다");
        assertEquals("T1", examples.get(0).block());
        assertEquals("00", examples.get(0).resultCode());
        assertEquals(false, examples.get(0).error());
        assertEquals("P3", examples.get(1).block(), "한 줄씩 다른 문단에 적은 XML을 이어 읽는다");
        assertEquals(true, examples.get(1).error());
    }

    @Test
    void 성공코드는_필드_표의_샘플과_설명_에러코드_표의_정상_행에서_모은다() {
        List<Block> blocks = List.of(
                table(FIELD_HEADER, row("resultCode", "결과코드", "2", "1", "0", "0 : 정상, 99: 실패"),
                        row("resultMsg", "결과메시지", "50", "1", "NORMAL SERVICE", "결과메시지")),
                table(row("에러 코드", "에러 메시지", "설명"), row("0", "NORMAL_SERVICE", "정상"),
                        row("1", "APPLICATION_ERROR", "비정상 종료"), row("12", "ABNORMAL_SERVICE", "폐기")));

        List<Candidate> candidates = SuccessCodes.collect(blocks, List.of());

        assertEquals(List.of(new Candidate("0", Source.FIELD_TABLE, "T1"), new Candidate("0", Source.FIELD_TABLE, "T1"),
                new Candidate("0", Source.ERROR_TABLE, "T2")), candidates,
                "결과메시지 행의 NORMAL, 비정상, ABNORMAL은 정상 코드가 아니다");
    }

    @Test
    void 샘플이_비어_있거나_샘플_열이_없는_표에서는_후보를_만들지_않는다() {
        List<Block> blocks = List.of(
                table(FIELD_HEADER, row("resultCode", "결과코드", "2", "1", "-", "결과코드")),
                table(row("Name", "Description", "Type"), row("resultCode", "결과코드", "string")),
                table(row("코드", "코드명"), row("1", "정상")));

        assertEquals(List.of(), SuccessCodes.collect(blocks, List.of()), "코드표(코드 | 코드명)는 에러코드 표가 아니다");
    }

    @Test
    void 헤더로_보내는_인증키를_알아본다() {
        List<String> evidence = new ArrayList<>();
        List<Block> blocks = List.of(
                new Block.Heading("요청 헤더에 인증키(Authorization)를 넣어 호출한다"),
                table(row("이름", "위치", "설명"), row("x-api-key", "header", "발급받은 키")));

        Set<String> kinds = AuthSignals.kinds(blocks, List.of(), evidence);

        assertEquals(Set.of(AuthSignals.HEADER_KEY), kinds);
        assertTrue(evidence.contains("헤더 키: 요청 헤더에 인증키(Authorization)를 넣어 호출한다 (P1)"), evidence.toString());
        assertTrue(evidence.contains("헤더 키: 위치가 header인 x-api-key (T2)"), evidence.toString());
    }

    @Test
    void 메시지_로깅_수준의_Header와_질의_문자열의_자리_표시는_인증_흔적이_아니다() {
        List<String> evidence = new ArrayList<>();
        List<Block> blocks = List.of(
                table(row("메시지 로깅 수준", "성공", "[O] Header [ ] Body")),
                new Block.Heading("http://apis.data.go.kr/6500000/trafficLightService4/trafficLightList4"
                        + "?serviceKey={인증키(URL Encode)}&page=10"));
        EngineOperation gateway = operation("http://apis.data.go.kr/6500000/trafficLightService4",
                "/trafficLightList4", "serviceKey", "query");

        assertEquals(Set.of(), AuthSignals.kinds(blocks, List.of(gateway), evidence), evidence.toString());
    }

    @Test
    void 게이트웨이가_아닌_주소에_키_파라미터가_없으면_인증_파라미터_없음이다() {
        List<String> evidence = new ArrayList<>();
        EngineOperation open = operation("https://www.example.go.kr/rest", "/getList", "pageNo", "query");

        Set<String> kinds = AuthSignals.kinds(List.of(), List.of(open), evidence);

        assertEquals(Set.of(AuthSignals.NO_KEY_PARAMETER), kinds);
        assertTrue(evidence.get(0).contains("https://www.example.go.kr/rest/getList"), evidence.toString());
    }

    private static EngineOperation operation(String baseUrl, String endpoint, String parameter, String in) {
        ObjectNode ir = JsonNodeFactory.instance.objectNode();
        ObjectNode api = ir.putObject("api");
        api.put("apiId", "List");
        api.put("baseUrl", baseUrl);
        api.put("endpoint", endpoint);
        ObjectNode param = api.putArray("requestParameters").addObject();
        param.put("name", parameter);
        param.put("in", in);
        return new EngineOperation(null, null, ir, null, false);
    }

    @SafeVarargs
    private static Block.Table table(List<String>... rows) {
        return new Block.Table(List.of(rows));
    }

    private static List<String> row(String... cells) {
        return List.of(cells);
    }
}
