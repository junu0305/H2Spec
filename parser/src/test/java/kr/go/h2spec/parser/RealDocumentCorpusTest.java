package kr.go.h2spec.parser;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import kr.go.h2spec.generator.ir.IrLoader;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * 공공데이터포털(data.go.kr)이 배포하는 실제 기관 문서로 파싱 결과를 통째로 검증한다.
 * 문서마다 표준 기술문서와 다른 배치를 하나씩 대표한다. 기대값(*.expected.json)은 문서를 직접 읽고 정리한
 * 오퍼레이션 주소·요청 파라미터·응답 필드 이름이며, 문서에 없는 serviceKey(게이트웨이)·경로 변수 파라미터는
 * 파서가 채운 그대로 담았다.
 * <ul>
 *   <li>khoa-realtime-sea-current: 세로 병합된 들여쓰기 열 때문에 행마다 열이 밀리는 표, Call Back URL N/A</li>
 *   <li>mohw-emergency-safety-org: "상세기능 정보" 띄어쓰기, 목차의 구간 제목, 빈 열이 낀 8칸 격자</li>
 *   <li>kdic-account-deposit: "오퍼레이션 명세" 제목과 "요청메시지 명세" 붙여쓰기</li>
 *   <li>jejudatahub-wifi-usage: 공통 파라미터, JSON 예제뿐인 응답, 주소의 경로 변수</li>
 *   <li>nongsaro-eco-farming: "요청 URL :" 문단과 요청변수/응답변수 표</li>
 *   <li>kosha-certificate-check: 스킴 없는 Call Back URL</li>
 *   <li>its-traffic-light: 서비스 URL + 오퍼레이션명과 다른 요청 예제 주소</li>
 *   <li>jeju-folksong: 요청 표 첫 행의 END POINT URL, 항목구분 열 없는 표</li>
 *   <li>nec-ballot-counting-place (HWPX): "(가) 요청 메시지 명세" 형식의 구간 제목</li>
 * </ul>
 */
class RealDocumentCorpusTest {

    private static final String CORPUS = "/docs/corpus/";
    private final ObjectMapper objectMapper = new ObjectMapper();

    @TempDir
    Path tempDir;

    @ParameterizedTest
    @ValueSource(strings = {
            "khoa-realtime-sea-current.hwp",
            "mohw-emergency-safety-org.hwp",
            "kdic-account-deposit.hwp",
            "jejudatahub-wifi-usage.hwp",
            "nongsaro-eco-farming.hwp",
            "kosha-certificate-check.hwp",
            "its-traffic-light.hwp",
            "jeju-folksong.hwp",
            "nec-ballot-counting-place.hwpx"})
    void 실제_기관_문서의_오퍼레이션과_필드를_모두_읽는다(String fileName) throws Exception {
        Path document = resource(fileName);
        JsonNode expected = objectMapper.readTree(resource(fileName.replaceAll("\\.hwpx?$", ".expected.json")).toFile());

        List<ParsedApi> apis = fileName.endsWith(".hwpx")
                ? new HwpxSpecParser().parse(document)
                : new HwpSpecParser().parse(document);

        JsonNode operations = expected.get("operations");
        assertEquals(operations.size(), apis.size(), "오퍼레이션 수");
        for (int i = 0; i < apis.size(); i++) {
            JsonNode api = apis.get(i).ir().get("api");
            JsonNode want = operations.get(i);
            assertEquals(want.get("url").asText(), api.get("baseUrl").asText() + api.get("endpoint").asText());
            assertEquals(texts(want.get("request")), values(api.get("requestParameters"), "name"), "요청 파라미터");
            assertEquals(texts(want.get("response")), leafNames(api.get("responseFields")), "응답 필드");
            assertGeneratorAccepts(apis.get(i));
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"khoa-realtime-sea-current.hwp"})
    void 세로_병합으로_가려진_칸을_비워_열_위치를_지킨다(String fileName) throws Exception {
        // 들여쓴 자식 행의 첫 열이 세로 병합돼 행에서 빠지면 국문명이 항목크기 자리로 밀린다.
        // 열 주소대로 놓아야 obsrvnYmd의 설명이 "timestamp"가 아니라 "관측일자"가 된다
        JsonNode fields = new HwpSpecParser().parse(resource(fileName)).get(0).ir().get("api").get("responseFields");

        JsonNode observed = null;
        for (JsonNode field : fields) {
            if (field.get("path").asText().endsWith(".obsrvnYmd")) {
                observed = field;
            }
        }
        assertEquals("관측일자", observed.get("description").asText());
        assertEquals("string", observed.get("type").asText());
    }

    /** 산출한 IR이 generator의 검증을 통과해야 다음 단계로 넘길 수 있다 */
    private void assertGeneratorAccepts(ParsedApi parsed) throws Exception {
        Path irFile = tempDir.resolve(parsed.apiId() + ".json");
        Files.writeString(irFile, parsed.ir().toPrettyString());
        assertEquals(parsed.apiId(), new IrLoader().load(irFile).api().apiId());
    }

    private Path resource(String name) throws Exception {
        return Path.of(getClass().getResource(CORPUS + name).toURI());
    }

    private List<String> texts(JsonNode array) {
        List<String> texts = new ArrayList<>();
        array.forEach(node -> texts.add(node.asText()));
        return texts;
    }

    private List<String> values(JsonNode nodes, String key) {
        List<String> values = new ArrayList<>();
        nodes.forEach(node -> values.add(node.get(key).asText()));
        return values;
    }

    private List<String> leafNames(JsonNode fields) {
        List<String> names = new ArrayList<>();
        fields.forEach(field -> {
            String path = field.get("path").asText();
            names.add(path.substring(path.lastIndexOf('.') + 1).replace("[]", ""));
        });
        return names;
    }
}
