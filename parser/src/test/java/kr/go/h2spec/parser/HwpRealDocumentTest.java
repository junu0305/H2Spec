package kr.go.h2spec.parser;

import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 공공데이터포털이 배포하는 실제 기관 HWP 문서로 파싱을 검증한다.
 * 픽스처: 국민연금공단_오픈API활용가이드_장애연금심사현황_v2.0.hwp (data.go.kr)
 * <p>
 * 이 문서는 표준 기술문서와 구조가 달라 다음을 함께 확인한다.
 * Call Back URL이 N/A라 서비스URL 표에서 기본 주소를 찾고, 정보 표를 첫 셀로 알아보며,
 * 반복 데이터 필드마다 항목구분에 0..n이 붙어 있어도 필드로 읽는다.
 */
class HwpRealDocumentTest {

    @Test
    void 실제_기관_HWP_문서에서_오퍼레이션을_추출한다() throws Exception {
        List<ParsedApi> apis = new HwpSpecParser().parse(fixture());

        assertEquals(1, apis.size());
        JsonNode api = apis.get(0).ir().get("api");
        assertEquals("JgmtSttusInfoSearchV2", api.get("apiId").asText());
        assertEquals("http://apis.data.go.kr/B552015/NpsLsnAnntyJgmtInfoInqireServiceV2",
                api.get("baseUrl").asText());
        assertEquals("/getJgmtSttusInfoSearchV2", api.get("endpoint").asText());
        assertEquals("HWP", apis.get(0).ir().get("metadata").get("sourceFormat").asText());
    }

    @Test
    void 카디널리티가_붙은_반복_필드도_응답에_담는다() throws Exception {
        JsonNode fields = new HwpSpecParser().parse(fixture()).get(0)
                .ir().get("api").get("responseFields");

        // 페이징 메타 3개 + 반복 데이터 필드 11개
        assertEquals(14, fields.size(), fields.toString());
        assertTrue(hasPath(fields, "response.body.items.item[].lsnDg1Cnt"), fields.toString());
        assertTrue(hasPath(fields, "response.body.totalCount"));
    }

    @Test
    void 자료생성년월은_string으로_추론한다() throws Exception {
        JsonNode fields = new HwpSpecParser().parse(fixture()).get(0)
                .ir().get("api").get("responseFields");

        JsonNode ym = find(fields, "response.body.items.item[].dataCrtYm");
        assertEquals("string", ym.get("type").asText());
    }

    private Path fixture() throws Exception {
        return Path.of(getClass().getResource("/docs/nps-disability-review.hwp").toURI());
    }

    private boolean hasPath(JsonNode fields, String path) {
        return find(fields, path) != null;
    }

    private JsonNode find(JsonNode fields, String path) {
        for (JsonNode f : fields) {
            if (path.equals(f.get("path").asText())) {
                return f;
            }
        }
        return null;
    }
}
