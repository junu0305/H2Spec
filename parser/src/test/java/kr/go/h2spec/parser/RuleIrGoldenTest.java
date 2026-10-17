package kr.go.h2spec.parser;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * rule 엔진이 낸 IR 전체를 golden 파일과 비교한다. RealDocumentCorpusTest는 주소·요청 이름·응답 필드 끝 이름만 보므로
 * 타입·필수 여부·경로·설명·노트·errorSpec이 바뀌어도 잡지 못한다. 주소 고르기 같은 IR 규칙({@link IrConventions})은 llm 엔진과 같이
 * 쓰므로, llm 엔진에 맞춰 고치다 rule 엔진의 IR이 조용히 바뀌지 않게 지킨다.
 * <p>
 * 의도해서 바꿨으면 {@code ./gradlew :parser:writeRuleIrGolden}으로 golden 파일을 다시 쓰고, 바뀐 내용을 검토해 함께 커밋한다.
 * 변환 시각(parsedAt)과 파서 버전(parserVersion)은 실행·릴리스마다 달라 비교하지 않는다.
 */
class RuleIrGoldenTest {

    /** golden 파일을 다시 쓰라는 시스템 속성. writeRuleIrGolden 태스크가 켠다 */
    private static final String WRITE = "h2spec.golden.write";
    /** 테스트 작업 디렉터리(모듈 디렉터리) 기준. 다시 쓸 때 빌드 디렉터리가 아니라 저장소의 파일을 고쳐야 한다 */
    private static final Path GOLDEN_DIR = Path.of("src/test/resources/golden/rule-ir");
    private static final ObjectMapper MAPPER = new ObjectMapper();

    @ParameterizedTest
    @ValueSource(strings = {
            "corpus/its-traffic-light.hwp",
            "corpus/jeju-folksong.hwp",
            "corpus/jejudatahub-wifi-usage.hwp",
            "corpus/kdic-account-deposit.hwp",
            "corpus/khoa-realtime-sea-current.hwp",
            "corpus/kosha-certificate-check.hwp",
            "corpus/mohw-emergency-safety-org.hwp",
            "corpus/nec-ballot-counting-place.hwpx",
            "corpus/nongsaro-eco-farming.hwp",
            "msrstn-info.docx",
            "msrstn-info.hwp",
            "nps-disability-review.hwp"})
    void rule_엔진의_IR_전체와_건너뛴_목록이_golden_파일과_같다(String name) throws Exception {
        Path document = Path.of(getClass().getResource("/docs/" + name).toURI());
        ObjectNode actual = snapshot(document);
        Path golden = GOLDEN_DIR.resolve(Path.of(name).getFileName() + ".json");

        if (Boolean.getBoolean(WRITE)) {
            Files.createDirectories(GOLDEN_DIR);
            Files.writeString(golden, MAPPER.writerWithDefaultPrettyPrinter().writeValueAsString(actual) + "\n");
            return;
        }
        assertTrue(Files.exists(golden), "golden 파일이 없습니다: " + golden.toAbsolutePath()
                + " (./gradlew :parser:writeRuleIrGolden으로 만든다)");
        assertEquals(MAPPER.readTree(golden.toFile()), actual, name + "의 rule IR이 golden 파일과 다릅니다. 의도한 변경이면 "
                + "./gradlew :parser:writeRuleIrGolden으로 다시 쓰고 바뀐 내용을 검토한다");
    }

    /** 문서의 rule IR과 건너뛴 목록 */
    private static ObjectNode snapshot(Path document) throws Exception {
        SpecParseResult result = new RuleSpecParser().parse(document);
        ObjectNode snapshot = MAPPER.createObjectNode();
        ArrayNode skipped = snapshot.putArray("skipped");
        result.skipped().forEach(skipped::add);
        ArrayNode apis = snapshot.putArray("apis");
        for (ParsedApi api : result.apis()) {
            ObjectNode ir = api.ir().deepCopy();
            ir.withObject("/metadata").remove("parsedAt");
            ir.withObject("/metadata").remove("parserVersion");
            apis.add(ir);
        }
        return snapshot;
    }
}
