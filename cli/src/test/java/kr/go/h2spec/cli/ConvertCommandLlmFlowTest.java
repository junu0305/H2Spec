package kr.go.h2spec.cli;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import kr.go.h2spec.parser.llm.LlmSpecParser;
import kr.go.h2spec.parser.llm.client.LlmClient;
import kr.go.h2spec.parser.llm.client.LlmProviderFactory;
import kr.go.h2spec.parser.llm.client.LlmResponse;
import kr.go.h2spec.parser.llm.client.LlmSettings;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import picocli.CommandLine;

import java.io.IOException;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.*;

/**
 * convert --engine llm이 실제 llm 엔진({@link LlmSpecParser})을 끝까지 쓰는 흐름. 회사 클라이언트만 parser-llm의 녹화 응답을
 * 돌려주는 가짜로 바꿔 키와 네트워크 없이 돈다. 엔진 조립(모델·추론 깊이·결과 캐시), IR의 metadata.engine·llm, 생성 코드, 엔진 줄을 함께 본다.
 */
class ConvertCommandLlmFlowTest {

    private static final String KEY = "sk-ant-api03-flow-test-secret";
    private static final List<String> API_IDS = List.of("MsrstnList", "NearbyMsrstnList", "TMStdrCrdnt");
    /** 녹화에는 사용량이 없어 정해 둔 값이다. 입력 셋을 더하면 17500 */
    private static final LlmResponse.Usage USAGE = new LlmResponse.Usage(12_000, 3_000, 2_500, 4_200, 1_000);

    @TempDir
    Path tempDir;

    @Test
    void 녹화한_응답으로_명세_문서를_llm_엔진으로_변환해_IR과_코드를_쓴다() throws Exception {
        ReplayProvider claude = new ReplayProvider(recording("msrstn-info.json"));
        Path output = tempDir.resolve("output");

        Run run = run(claude, "--input", docPath("msrstn-info.docx").toString(), "--output", output.toString(),
                "--cache-dir", tempDir.resolve("cache").toString());

        assertEquals(0, run.exit(), run.err());
        assertEquals("문서 내용을 claude(claude-opus-5-5)로 보냅니다. 비공개 문서는 --engine rule로 변환하세요."
                + System.lineSeparator(), run.err(), "교차검증 경고도 건너뛴 오퍼레이션도 없는 문서다");
        assertEquals("엔진: llm (claude / claude-opus-5-5), 캐시 없음, 토큰 입력 17500 / 출력 4200 (thinking 포함)",
                run.out().lines().findFirst().orElseThrow());
        assertEquals(1, claude.calls);
        assertEquals("claude-opus-5-5", claude.settings.model());
        assertEquals("medium", claude.settings.effort());
        assertEquals(KEY, claude.settings.apiKey());
        for (String apiId : API_IDS) {
            JsonNode metadata = readIr(output.resolve("ir/" + apiId + ".json")).get("metadata");
            assertEquals("llm", metadata.get("engine").asText(), apiId);
            assertEquals("claude", metadata.at("/llm/provider").asText(), apiId);
            assertEquals("claude-opus-5-5", metadata.at("/llm/model").asText(), apiId);
            assertEquals("medium", metadata.at("/llm/effort").asText(), apiId);
            assertFalse(metadata.toString().contains("17500"), "토큰 사용량은 IR에 넣지 않는다");
        }
        assertTrue(Files.exists(output.resolve("kr/go/h2spec/client/msrstnlist/MsrstnListClient.java")));
        assertTrue(Files.exists(output.resolve("kr/go/h2spec/client/tmstdrcrdnt/TMStdrCrdntClient.java")));
    }

    @Test
    void 같은_캐시_디렉터리로_다시_변환하면_LLM을_부르지_않고_캐시_사용으로_알린다() throws Exception {
        ReplayProvider claude = new ReplayProvider(recording("msrstn-info.json"));
        Path cacheDir = tempDir.resolve("cache");
        Path doc = docPath("msrstn-info.docx");

        Run first = run(claude, "--input", doc.toString(), "--output", tempDir.resolve("first").toString(),
                "--cache-dir", cacheDir.toString());
        Run second = run(claude, "--input", doc.toString(), "--output", tempDir.resolve("second").toString(),
                "--cache-dir", cacheDir.toString());

        assertEquals(0, first.exit(), first.err());
        assertEquals(0, second.exit(), second.err());
        assertEquals(1, claude.calls, "두 번째 실행은 결과 캐시에서 꺼낸다");
        assertEquals("엔진: llm (claude / claude-opus-5-5), 캐시 사용, 토큰 입력 0 / 출력 0 (thinking 포함)",
                second.out().lines().findFirst().orElseThrow());
        try (Stream<Path> files = Files.list(cacheDir)) {
            assertEquals(1, files.count());
        }
        for (String apiId : API_IDS) {
            ObjectNode fromCall = readIr(tempDir.resolve("first/ir/" + apiId + ".json"));
            ObjectNode fromCache = readIr(tempDir.resolve("second/ir/" + apiId + ".json"));
            // 변환 시각은 실행마다 다르다
            fromCache.withObject("/metadata").put("parsedAt", fromCall.at("/metadata/parsedAt").asText());
            assertEquals(fromCall, fromCache, "같은 입력과 설정에서는 같은 IR이 나온다: " + apiId);
        }
    }

    @Test
    void no_cache면_cache_dir을_줘도_결과_캐시를_쓰지_않고_매번_LLM을_부른다() throws Exception {
        ReplayProvider claude = new ReplayProvider(recording("msrstn-info.json"));
        Path cacheDir = tempDir.resolve("cache");
        Path doc = docPath("msrstn-info.docx");

        for (String name : List.of("first", "second")) {
            Run run = run(claude, "--input", doc.toString(), "--output", tempDir.resolve(name).toString(),
                    "--no-cache", "--cache-dir", cacheDir.toString());

            assertEquals(0, run.exit(), run.err());
            assertTrue(run.out().startsWith("엔진: llm (claude / claude-opus-5-5), 캐시 없음, 토큰 입력 17500 / 출력 4200"),
                    run.out());
        }
        assertEquals(2, claude.calls);
        assertFalse(Files.exists(cacheDir));
    }

    /** 실제 llm 엔진 조립({@link LlmSpecParser#of})을 쓰고, 회사 클라이언트만 녹화를 재생하는 가짜로 둔다 */
    private Run run(ReplayProvider provider, String... args) {
        ConvertCommand command = new ConvertCommand(() -> Map.of("ANTHROPIC_API_KEY", KEY), List.of(provider),
                LlmSpecParser::of);
        StringWriter out = new StringWriter();
        StringWriter err = new StringWriter();
        CommandLine cli = new CommandLine(command);
        cli.setOut(new PrintWriter(out));
        cli.setErr(new PrintWriter(err));
        String[] withEngine = Stream.concat(Stream.of("--engine", "llm"), Stream.of(args)).toArray(String[]::new);
        int exit = cli.execute(withEngine);
        return new Run(exit, out.toString(), err.toString());
    }

    private Path docPath(String fileName) throws Exception {
        return Path.of(getClass().getResource("/docs/" + fileName).toURI());
    }

    private ObjectNode readIr(Path file) throws IOException {
        return (ObjectNode) new ObjectMapper().readTree(file.toFile());
    }

    /**
     * parser-llm의 녹화한 중간 스키마 JSON. 같은 녹화를 모듈마다 복사해 두면 한쪽만 고쳐져도 알 수 없어 저장소 루트에서 읽는다.
     * Gradle은 저장소 루트를 시스템 속성으로 넘기고(cli/build.gradle), IDE에서 모듈 디렉터리로 돌리면 그 부모를 쓴다.
     */
    private static String recording(String name) throws IOException {
        String root = System.getProperty("h2spec.repoRoot");
        Path repoRoot = root != null ? Path.of(root) : Path.of("").toAbsolutePath().getParent();
        return Files.readString(repoRoot.resolve("parser-llm/src/test/resources/llm-recordings/claude/" + name));
    }

    /** 종료 코드와 표준 출력·표준 오류 */
    private record Run(int exit, String out, String err) {
    }

    /** 녹화를 재생하는 클라이언트를 만드는 Claude. 받은 설정과 부른 횟수를 기록한다 */
    private static final class ReplayProvider implements LlmProviderFactory {

        private final String json;
        private LlmSettings settings;
        private int calls;

        ReplayProvider(String json) {
            this.json = json;
        }

        @Override
        public String id() {
            return "claude";
        }

        @Override
        public String defaultModel() {
            return "claude-opus-5-5";
        }

        @Override
        public String defaultEffort() {
            return "medium";
        }

        @Override
        public List<String> apiKeyEnvironmentVariables() {
            return List.of("ANTHROPIC_API_KEY");
        }

        @Override
        public LlmClient create(LlmSettings created) {
            settings = created;
            return request -> {
                calls++;
                return new LlmResponse(json, created.model(), USAGE, LlmResponse.Finish.COMPLETE);
            };
        }
    }
}
