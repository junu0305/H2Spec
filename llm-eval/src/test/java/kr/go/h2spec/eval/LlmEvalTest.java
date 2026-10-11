package kr.go.h2spec.eval;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;

import static org.junit.jupiter.api.Assertions.assertEquals;
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
