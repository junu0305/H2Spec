package kr.go.h2spec.eval;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 녹화 응답을 재생하는 llm 엔진. 응답은 parser-llm의 녹화 응답 그 자체를 쓴다. 회사를 부르지 않는다.
 */
class ReplayLlmEvalEngineTest {

    @TempDir
    Path tempDir;

    private final List<String> warnings = new ArrayList<>();

    @Test
    void 문서_파일_이름의_녹화_응답을_llm_엔진에_넣어_오퍼레이션을_낸다() throws Exception {
        Path responses = responses("msrstn-info.json", "msrstn-info.docx.json");

        List<EngineOperation> operations = engine(responses).extract(document("msrstn-info.docx"));

        assertEquals(List.of("MsrstnList", "NearbyMsrstnList", "TMStdrCrdnt"),
                operations.stream().map(EngineOperation::apiId).toList());
        assertTrue(operations.stream().allMatch(EngineOperation::extracted));
        assertTrue(operations.stream().noneMatch(EngineOperation::outOfScope));
        assertEquals("llm", operations.get(0).ir().path("metadata").path("engine").asText());
        assertEquals("replay", operations.get(0).ir().path("metadata").path("llm").path("provider").asText(),
                "녹화 응답은 어느 회사에서 왔는지 모르므로 재생이라고만 적는다");
        assertEquals("llm", engine(responses).name());
    }

    @Test
    void 지원_범위_밖으로_건너뛴_오퍼레이션도_정규화한_IR과_함께_낸다() throws Exception {
        Path responses = responses("jejudatahub-wifi-usage.json", "jejudatahub-wifi-usage.hwp.json");

        List<EngineOperation> operations = engine(responses)
                .extract(TestPaths.corpus("jejudatahub-wifi-usage.hwp"));

        assertEquals(1, operations.size());
        EngineOperation jeju = operations.get(0);
        assertTrue(jeju.outOfScope());
        assertTrue(jeju.extracted(), "재현율·정확도는 범위 판정 전의 추출 결과로 잰다");
        assertTrue(jeju.skipReason().startsWith("인증 방식 미지원"), jeju.skipReason());
    }

    @Test
    void 녹화_응답이_없으면_이유를_담아_실패한다() {
        IOException e = assertThrows(IOException.class,
                () -> engine(tempDir).extract(document("msrstn-info.docx")));

        assertEquals("녹화 응답이 없습니다: " + tempDir.resolve("msrstn-info.docx.json"), e.getMessage());
    }

    @Test
    void 스키마를_따르지_않은_응답은_llm_엔진이_읽지_못한_응답으로_실패한다() throws Exception {
        Files.writeString(tempDir.resolve("msrstn-info.docx.json"), "{\"serviceName\": \"측정소정보\"}");

        IOException e = assertThrows(IOException.class,
                () -> engine(tempDir).extract(document("msrstn-info.docx")));

        assertTrue(e.getMessage().startsWith("LLM 응답을 중간 스키마 JSON으로 읽지 못했습니다 (msrstn-info.docx, 2번 시도)"),
                e.getMessage());
    }

    private ReplayLlmEvalEngine engine(Path responses) {
        return new ReplayLlmEvalEngine(responses, warnings::add);
    }

    /** parser-llm의 녹화 응답을 문서 파일 이름으로 옮겨 둔 응답 디렉터리 */
    private Path responses(String recording, String responseName) throws IOException {
        Path responses = Files.createDirectories(tempDir.resolve("responses"));
        Files.copy(TestPaths.repoRoot().resolve("parser-llm/src/test/resources/llm-recordings/claude").resolve(recording),
                responses.resolve(responseName));
        return responses;
    }

    private static Path document(String fileName) {
        return TestPaths.repoRoot().resolve("parser/src/test/resources/docs").resolve(fileName);
    }
}
