package kr.go.h2spec.eval;

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
import static org.junit.jupiter.api.Assertions.assertFalse;
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

        // 같은 날 앞서 돌린 실행이 남긴, 이번에는 평가하지 않는 문서의 JSON
        Path reports = tempDir.resolve("llm-eval/build/reports/2026-10-11-rule");
        Path stale = Files.createDirectories(reports.resolve("documents")).resolve("removed.hwp.json");
        Files.writeString(stale, "{}");

        int code = run("--set", "A", "--engine", "rule", "--root", tempDir.toString());

        assertEquals(0, code, err());
        String summary = Files.readString(reports.resolve("summary.md"));
        assertTrue(summary.contains("| 오퍼레이션 재현율 | 100.0% (1/1) |"), summary);
        assertTrue(summary.contains("| generator 통과율 | 100.0% (1/1) |"), summary);
        assertTrue(Files.exists(reports.resolve("documents/kdic-account-deposit.hwp.json")));
        assertFalse(Files.exists(stale), "이번 실행의 문서별 JSON만 남는다");
        assertTrue(out().contains("generator 통과 1/1"), out());
    }

    @Test
    void llm_엔진은_녹화_응답을_재생해_평가하고_엔진과_녹화_디렉터리_이름의_디렉터리에_리포트를_쓴다() throws Exception {
        Path corpus = Files.createDirectories(tempDir.resolve(DataSets.CORPUS));
        Files.copy(TestPaths.corpus("its-traffic-light.hwp"), corpus.resolve("its-traffic-light.hwp"));
        Files.copy(TestPaths.corpus("its-traffic-light.expected.json"), corpus.resolve("its-traffic-light.expected.json"));
        Path responses = Files.createDirectories(tempDir.resolve("responses"));
        Files.copy(TestPaths.repoRoot().resolve("parser-llm/src/test/resources/llm-recordings/claude/its-traffic-light.json"),
                responses.resolve("its-traffic-light.hwp.json"));

        int code = run("--set", "A", "--engine", "llm", "--responses", responses.toString(), "--root", tempDir.toString());

        assertEquals(0, code, err());
        // 같은 날 다른 녹화(모델 비교 등)를 돌려도 덮어쓰지 않게 녹화 디렉터리 이름을 붙인다
        Path reports = tempDir.resolve("llm-eval/build/reports/2026-10-11-llm-responses");
        String summary = Files.readString(reports.resolve("summary.md"));
        assertTrue(summary.startsWith("# llm 엔진 평가 — 세트 A\n"), summary);
        assertTrue(summary.contains("| 오퍼레이션 재현율 | 100.0% (1/1) |"), summary);
        String document = Files.readString(reports.resolve("documents/its-traffic-light.hwp.json"));
        // 녹화는 실제 응답이다. 신호연계의 page는 body 밖 response 바로 아래에 있다(rule 엔진은 items.item[] 아래로 본다)
        assertTrue(document.contains("response.page.page"), "llm 엔진의 응답 경로를 문서별 JSON에서 볼 수 있다: " + document);
        assertTrue(out().contains("녹화 응답: " + responses), out());
    }

    @Test
    void 녹화_파일은_확장자를_뗀_이름도_찾고_녹화가_없는_문서는_못_찾은_것으로_세지_않고_지표에서_뺀다() throws Exception {
        Path corpus = Files.createDirectories(tempDir.resolve(DataSets.CORPUS));
        for (String name : List.of("its-traffic-light", "kdic-account-deposit")) {
            Files.copy(TestPaths.corpus(name + ".hwp"), corpus.resolve(name + ".hwp"));
            Files.copy(TestPaths.corpus(name + ".expected.json"), corpus.resolve(name + ".expected.json"));
        }
        // 같은 문서의 DOCX·HWP판. 확장자를 뗀 이름의 녹화는 어느 판에서 받았는지 알 수 없어 어느 쪽에도 쓰지 않는다
        Path extra = Files.createDirectories(tempDir.resolve("parser/src/test/resources/docs"));
        for (String name : List.of("msrstn-info.docx", "msrstn-info.hwp")) {
            Files.copy(TestPaths.repoRoot().resolve("parser/src/test/resources/docs/" + name), extra.resolve(name));
        }
        Path responses = Files.createDirectories(tempDir.resolve("claude"));
        // 저장소의 llm-recordings가 쓰는 이름 그대로다
        for (String name : List.of("its-traffic-light.json", "msrstn-info.json")) {
            Files.copy(TestPaths.repoRoot().resolve("parser-llm/src/test/resources/llm-recordings/claude/" + name),
                    responses.resolve(name));
        }

        int code = run("--set", "A", "--engine", "llm", "--responses", responses.toString(), "--root", tempDir.toString());

        assertEquals(0, code, err());
        String summary = Files.readString(tempDir.resolve("llm-eval/build/reports/2026-10-11-llm-claude/summary.md"));
        assertTrue(summary.contains("| 오퍼레이션 재현율 | 100.0% (1/1) |"), "녹화가 없는 kdic은 재현율에 들지 않는다: " + summary);
        assertTrue(summary.contains("녹화 응답이 없습니다"), summary);
        // kdic은 정답 파일이 있지만 녹화가 없어 지표에서 빼고, msrstn-info 두 판은 정답 파일이 없다
        assertTrue(summary.contains("(정답 있음 1개, 정답 없음 2개, 지표에서 뺌 1개)"), summary);
        assertTrue(summary.contains("msrstn-info.hwp.json (지표에서 뺌)"), "msrstn-info.json을 어느 판에도 쓰지 않는다: " + summary);
        assertTrue(out().contains("녹화 응답이 없는 문서 3개는 지표에서 뺍니다"), out());
    }

    @Test
    void 녹화_응답이_하나도_맞지_않으면_빈_리포트를_쓰지_않고_실패로_끝난다() throws Exception {
        Path corpus = Files.createDirectories(tempDir.resolve(DataSets.CORPUS));
        Files.copy(TestPaths.corpus("its-traffic-light.hwp"), corpus.resolve("its-traffic-light.hwp"));
        Files.copy(TestPaths.corpus("its-traffic-light.expected.json"), corpus.resolve("its-traffic-light.expected.json"));
        Path responses = Files.createDirectories(tempDir.resolve("empty"));

        int code = run("--set", "A", "--engine", "llm", "--responses", responses.toString(), "--root", tempDir.toString());

        assertEquals(1, code);
        assertTrue(err().contains("녹화 응답 디렉터리에 평가할 문서의 응답이 하나도 없습니다"), err());
        assertFalse(Files.exists(tempDir.resolve("llm-eval/build/reports/2026-10-11-llm-empty")));
    }

    @Test
    void proxy만_주면_세트_A_없이_대리_지표만_쓴다() throws Exception {
        Path documents = Files.createDirectories(tempDir.resolve("set-c/sub"));
        Files.copy(TestPaths.repoRoot().resolve("parser/src/test/resources/docs/msrstn-info.docx"),
                documents.resolve("msrstn-info.docx"));
        Path reports = tempDir.resolve("reports");

        int code = run("--proxy", tempDir.resolve("set-c").toString(), "--out", reports.toString(),
                "--root", tempDir.toString());

        assertEquals(0, code, err());
        assertTrue(Files.readString(reports.resolve("proxy-summary.md")).contains("- 문서: 1개"),
                "하위 디렉터리의 문서도 센다");
        assertTrue(Files.readString(reports.resolve("proxy.json")).contains("msrstn-info.docx"));
        assertFalse(Files.exists(reports.resolve("summary.md")), "세트 A는 평가하지 않는다");
        assertTrue(out().contains("대리 지표 요약: " + reports.resolve("proxy-summary.md")), out());
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
