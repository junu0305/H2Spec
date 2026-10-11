package kr.go.h2spec.eval;

import kr.go.h2spec.eval.DataSets.EvalDocument;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 명령행 진입점과 세트 A 구성. 저장소 루트 아래의 정해진 자리에서 문서와 정답 파일을 찾고, 리포트를 날짜·엔진 디렉터리에 쓴다.
 */
class LlmEvalTest {

    private static final LocalDate DATE = LocalDate.of(2026, 10, 11);

    @TempDir
    Path tempDir;

    private final ByteArrayOutputStream out = new ByteArrayOutputStream();
    private final ByteArrayOutputStream err = new ByteArrayOutputStream();

    @Test
    void 세트_A는_정답_있는_corpus_9개와_정답이_아직_없는_문서_4개다() throws Exception {
        List<EvalDocument> documents = DataSets.setA(TestPaths.repoRoot());

        assertEquals(13, documents.size());
        List<EvalDocument> corpus = documents.subList(0, 9);
        assertTrue(corpus.stream().allMatch(document -> document.gold() != null), corpus.toString());
        assertEquals("its-traffic-light.hwp", corpus.get(0).document().getFileName().toString());
        assertEquals("nongsaro-eco-farming.expected.json", corpus.get(8).gold().getFileName().toString());
        // msrstn-info 세 판과 nps는 단위 테스트가 일부 값만 확인할 뿐 정답 파일이 없다
        assertEquals(List.of("msrstn-info.docx", "msrstn-info.hwp", "msrstn-info.hwpx", "nps-disability-review.hwp"),
                documents.subList(9, 13).stream().map(document -> document.document().getFileName().toString()).toList());
        documents.subList(9, 13).forEach(document -> assertNull(document.gold()));
    }

    @Test
    void 저장소_루트를_잘못_주면_세트_A를_찾지_못했다고_알린다() {
        int code = run("--set", "A", "--root", tempDir.toString());

        assertEquals(1, code);
        assertTrue(err().contains("세트 A의 corpus를 찾지 못했습니다"), err());
    }

    @Test
    void 세트_A를_평가해_날짜와_엔진_이름의_디렉터리에_리포트를_쓴다() throws Exception {
        // corpus 하나만 둔 저장소 모양을 만든다. javac를 한 번만 돌리려는 것이다
        Path corpus = Files.createDirectories(tempDir.resolve(DataSets.CORPUS));
        Files.copy(TestPaths.corpus("kdic-account-deposit.hwp"), corpus.resolve("kdic-account-deposit.hwp"));
        Files.copy(TestPaths.corpus("kdic-account-deposit.expected.json"),
                corpus.resolve("kdic-account-deposit.expected.json"));

        int code = run("--set", "A", "--engine", "rule", "--root", tempDir.toString());

        assertEquals(0, code, err());
        Path reports = tempDir.resolve("llm-eval/build/reports/2026-10-11-rule");
        String summary = Files.readString(reports.resolve("summary.md"));
        assertTrue(summary.contains("| 오퍼레이션 재현율 | 100.0% (1/1) |"), summary);
        assertTrue(summary.contains("| generator 통과율 | 100.0% (1/1) |"), summary);
        assertTrue(Files.exists(reports.resolve("documents/kdic-account-deposit.hwp.json")));
        assertTrue(out().contains("generator 통과 1/1"), out());
    }

    @Test
    void llm_엔진은_녹화_응답을_재생해_평가하고_엔진_이름의_디렉터리에_리포트를_쓴다() throws Exception {
        Path corpus = Files.createDirectories(tempDir.resolve(DataSets.CORPUS));
        Files.copy(TestPaths.corpus("its-traffic-light.hwp"), corpus.resolve("its-traffic-light.hwp"));
        Files.copy(TestPaths.corpus("its-traffic-light.expected.json"), corpus.resolve("its-traffic-light.expected.json"));
        Path responses = Files.createDirectories(tempDir.resolve("responses"));
        Files.copy(TestPaths.repoRoot().resolve("parser-llm/src/test/resources/llm-recordings/claude/its-traffic-light.json"),
                responses.resolve("its-traffic-light.hwp.json"));

        int code = run("--set", "A", "--engine", "llm", "--responses", responses.toString(), "--root", tempDir.toString());

        assertEquals(0, code, err());
        Path reports = tempDir.resolve("llm-eval/build/reports/2026-10-11-llm");
        String summary = Files.readString(reports.resolve("summary.md"));
        assertTrue(summary.startsWith("# llm 엔진 평가 — 세트 A\n"), summary);
        assertTrue(summary.contains("| 오퍼레이션 재현율 | 100.0% (1/1) |"), summary);
        String document = Files.readString(reports.resolve("documents/its-traffic-light.hwp.json"));
        assertTrue(document.contains("[교차검증]"), "llm 엔진의 검수 노트를 문서별 JSON에서 볼 수 있다: " + document);
        assertTrue(out().contains("녹화 응답: " + responses), out());
    }

    @Test
    void 대리_지표만_내면_정답_세트는_평가하지_않는다() throws Exception {
        Path documents = Files.createDirectories(tempDir.resolve("setC/sub"));
        Files.copy(TestPaths.corpus("jeju-folksong.hwp"), documents.resolve("jeju-folksong.hwp"));
        Files.writeString(documents.resolve("notes.txt"), "문서가 아닌 파일은 건너뛴다");
        Path reports = tempDir.resolve("out");

        int code = run("--proxy", tempDir.resolve("setC").toString(), "--out", reports.toString());

        assertEquals(0, code, err());
        assertTrue(Files.exists(reports.resolve("proxy-summary.md")));
        assertTrue(Files.exists(reports.resolve("proxy.json")));
        assertTrue(Files.notExists(reports.resolve("summary.md")));
        assertTrue(out().contains("sub/jeju-folksong.hwp: (b) (d)"), out());
    }

    @Test
    void 잘못된_옵션은_사용법과_함께_종료_코드_2로_끝난다() {
        assertEquals(2, run("--engine", "llm"));
        assertTrue(err().contains("사용법"), err());
        assertEquals(0, run("--help"));
        assertTrue(out().contains("LLM이나 외부 서비스를 부르지 않는다"), out());
    }

    private int run(String... args) {
        return LlmEval.run(args, new PrintStream(out, true, StandardCharsets.UTF_8),
                new PrintStream(err, true, StandardCharsets.UTF_8), DATE);
    }

    private String out() {
        return out.toString(StandardCharsets.UTF_8);
    }

    private String err() {
        return err.toString(StandardCharsets.UTF_8);
    }
}
