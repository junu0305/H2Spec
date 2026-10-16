package kr.go.h2spec.cli;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import kr.go.h2spec.parser.ParsedApi;
import kr.go.h2spec.parser.SpecParseException;
import kr.go.h2spec.parser.SpecParseResult;
import kr.go.h2spec.parser.SpecParseResult.Engine;
import kr.go.h2spec.parser.SpecParseResult.Operation;
import kr.go.h2spec.parser.SpecParseResult.TokenUsage;
import kr.go.h2spec.parser.SpecParser;
import kr.go.h2spec.parser.llm.LlmSpecParser;
import kr.go.h2spec.parser.llm.client.LlmClient;
import kr.go.h2spec.parser.llm.client.LlmProviderFactory;
import kr.go.h2spec.parser.llm.client.LlmProviders;
import kr.go.h2spec.parser.llm.client.LlmSettings;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import picocli.CommandLine;

import java.io.IOException;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.Supplier;

import static org.junit.jupiter.api.Assertions.*;

/**
 * convert의 엔진 옵션. 환경변수와 llm 엔진을 가짜로 바꿔 실제 환경변수·키·네트워크 없이 본다.
 * 가짜 엔진은 문서를 읽지 않고 파일 이름마다 정해 둔 결과를 돌려주므로, 명세 문서는 확장자만 맞춘 가짜 파일로 둔다.
 */
class ConvertCommandEngineTest {

    private static final String KEY = "sk-ant-api03-cli-test-secret";
    private static final String NOTICE =
            "문서 내용을 claude(claude-sonnet-5-5, 추론 깊이 medium)로 보냅니다. 비공개 문서는 --engine rule로 변환하세요.";
    private static final LlmProviderFactory CLAUDE = new FakeProvider("claude", List.of("ANTHROPIC_API_KEY"));
    private static final LlmProviderFactory OPENAI = new FakeProvider("openai", List.of("OPENAI_API_KEY"));
    /** 쓰이면 안 되는 엔진. rule 엔진 실행에서 쓴다 */
    private static final SpecParser UNUSED = document -> {
        throw new AssertionError("llm 엔진을 쓰면 안 된다: " + document);
    };

    @TempDir
    Path tempDir;

    /** 마지막으로 만든 명령의 환경변수와 llm 엔진 팩토리 */
    private RecordingEnvironment environment;
    private RecordingFactory factory;

    @Test
    void 기본_엔진과_rule_엔진은_키가_있어도_회사를_정하지_않고_환경변수도_읽지_않으며_출력이_그대로다() throws Exception {
        Path inputDir = Files.createDirectories(tempDir.resolve("input"));
        Files.copy(docPath("msrstn-info.docx"), inputDir.resolve("msrstn-info.docx"));
        Files.copy(irPath(), inputDir.resolve("schema-example.json"));
        Map<String, String> keys = Map.of("ANTHROPIC_API_KEY", KEY, "OPENAI_API_KEY", "o-key",
                "H2SPEC_LLM_PROVIDER", "claude", "H2SPEC_LLM_MODEL", "claude-sonnet-5-5");

        for (Path input : List.of(inputDir.resolve("msrstn-info.docx"), inputDir)) {
            String expectedOutput = out(input.getFileName() + "-expected");
            Run expected = normalized(runDefault("convert", "--input", input.toString(), "--output", expectedOutput),
                    expectedOutput);
            for (List<String> engineOption : List.of(List.<String>of(), List.of("--engine", "rule"))) {
                String actualOutput = out(input.getFileName() + "-actual" + engineOption.size());
                List<String> args = new ArrayList<>(engineOption);
                args.addAll(List.of("--input", input.toString(), "--output", actualOutput));

                Run actual = normalized(run(command(List.of(CLAUDE, OPENAI), keys, UNUSED), args.toArray(String[]::new)),
                        actualOutput);

                assertEquals(expected, actual, args.toString());
                assertEquals(0, environment.reads, "rule 엔진은 환경변수를 읽지 않는다: " + args);
                assertEquals(0, factory.calls, "rule 엔진은 llm 엔진을 만들지 않는다: " + args);
            }
            assertEquals(0, expected.exit());
            assertFalse(expected.out().contains("엔진:"), expected.out());
        }
        Run directory = runDefault("convert", "--input", inputDir.toString(), "--output", out("summary"));
        assertEquals("2개 중 2개 변환 성공, 0개 실패", lastLine(directory.out()), "rule 엔진의 요약에는 토큰 합계가 없다");
    }

    @Test
    void 외부_전송_안내는_실행마다_한_번만_내고_키는_어떤_출력에도_나오지_않는다() throws Exception {
        Path inputDir = Files.createDirectories(tempDir.resolve("input"));
        Files.writeString(inputDir.resolve("a.hwp"), "fake");
        Files.writeString(inputDir.resolve("b.docx"), "fake");
        ParsedApi first = api("FirstApi");
        ParsedApi second = api("SecondApi");
        FakeParser parser = new FakeParser(Map.of(
                "a.hwp", () -> llmResult(false, new TokenUsage(10, 0, 0, 5), first),
                "b.docx", () -> llmResult(false, new TokenUsage(20, 0, 0, 6), second)));

        Run run = run(command(LlmProviders.ALL, Map.of("ANTHROPIC_API_KEY", KEY), parser),
                "--engine", "llm", "--input", inputDir.toString(), "--output", out("output"));

        assertEquals(0, run.exit(), run.err());
        assertEquals(1, run.err().lines().filter(NOTICE::equals).count(), run.err());
        assertEquals(KEY, factory.settings.apiKey(), "키는 엔진까지만 간다");
        assertEquals("claude", factory.provider.id());
        assertFalse(run.out().contains(KEY), run.out());
        assertFalse(run.err().contains(KEY), run.err());
        assertEquals(List.of("a.hwp", "b.docx"), parser.parsed);
    }

    @Test
    void ANTHROPIC_WORKSPACE_ID가_있으면_그_워크스페이스로_부르고_없으면_보내지_않는다() throws Exception {
        Path doc = Files.writeString(tempDir.resolve("a.hwp"), "fake");
        SpecParseResult result = llmResult(false, TokenUsage.NONE, api("FirstApi"));
        SpecParser parser = document -> result;

        run(command(LlmProviders.ALL, Map.of("ANTHROPIC_API_KEY", KEY, "ANTHROPIC_WORKSPACE_ID", " wrkspc_01Test "), parser),
                "--engine", "llm", "--input", doc.toString(), "--output", out("with"));
        String withWorkspace = factory.settings.workspaceId();
        run(command(LlmProviders.ALL, Map.of("ANTHROPIC_API_KEY", KEY), parser),
                "--engine", "llm", "--input", doc.toString(), "--output", out("without"));

        assertEquals("wrkspc_01Test", withWorkspace, "여러 워크스페이스에서 쓰는 키는 요청마다 워크스페이스를 밝혀야 한다");
        assertNull(factory.settings.workspaceId());
    }

    @Test
    void llm_엔진은_문서마다_엔진과_토큰을_알리고_디렉터리_요약에_토큰_합계를_붙인다() throws Exception {
        Path inputDir = Files.createDirectories(tempDir.resolve("input"));
        for (String name : List.of("a.hwp", "b.docx", "c.hwpx")) {
            Files.writeString(inputDir.resolve(name), "fake");
        }
        Files.copy(irPath(), inputDir.resolve("d.json"));
        ParsedApi first = api("FirstApi");
        ParsedApi second = api("SecondApi");
        ParsedApi third = api("ThirdApi");
        FakeParser parser = new FakeParser(Map.of(
                "a.hwp", () -> llmResult(false, new TokenUsage(1000, 200, 30, 400), first),
                "b.docx", () -> llmResult(false, new TokenUsage(5, 6, 7, 8), second),
                // 결과 캐시에서 꺼낸 문서는 LLM을 부르지 않아 토큰이 0이다
                "c.hwpx", () -> llmResult(true, TokenUsage.NONE, third)));

        Run run = run(command(LlmProviders.ALL, Map.of("ANTHROPIC_API_KEY", KEY), parser),
                "--engine", "llm", "--input", inputDir.toString(), "--output", out("output"));

        assertEquals(0, run.exit(), run.err());
        List<String> lines = run.out().lines().toList();
        assertEquals("엔진: llm (claude / claude-sonnet-5-5, 추론 깊이 medium), 캐시 없음, "
                        + "토큰 입력 1230 (프롬프트 캐시 읽기 200) / 출력 400 (thinking 포함)",
                lineAfter(lines, "=== a.hwp ==="), "입력은 캐시에서 읽고 쓴 입력까지 더한 보낸 입력 전체다");
        assertEquals("엔진: llm (claude / claude-sonnet-5-5, 추론 깊이 medium), 캐시 없음, "
                        + "토큰 입력 18 (프롬프트 캐시 읽기 6) / 출력 8 (thinking 포함)",
                lineAfter(lines, "=== b.docx ==="));
        assertEquals("엔진: llm (claude / claude-sonnet-5-5, 추론 깊이 medium), 캐시 사용, 토큰 입력 0 / 출력 0 (thinking 포함)",
                lineAfter(lines, "=== c.hwpx ==="));
        assertTrue(lineAfter(lines, "=== d.json ===").startsWith("생성: "), "IR JSON은 엔진을 쓰지 않는다: " + run.out());
        assertEquals(3, lines.stream().filter(line -> line.startsWith("엔진: ")).count(), run.out());
        assertEquals("4개 중 4개 변환 성공, 0개 실패 · LLM 토큰 합계 입력 1248 (프롬프트 캐시 읽기 206) / 출력 408",
                lastLine(run.out()));
    }

    @Test
    void 추론_깊이는_effort_옵션_H2SPEC_LLM_EFFORT_회사_기본값_순으로_정하고_회사가_받지_않는_값은_부르기_전에_멈춘다()
            throws Exception {
        Path doc = Files.writeString(tempDir.resolve("a.hwp"), "fake");
        SpecParseResult result = llmResult(false, TokenUsage.NONE, api("FirstApi"));
        SpecParser parser = document -> result;
        Map<String, String> keyOnly = Map.of("ANTHROPIC_API_KEY", KEY);
        Map<String, String> withEnvironment = Map.of("ANTHROPIC_API_KEY", KEY, "H2SPEC_LLM_EFFORT", "xhigh");

        run(command(LlmProviders.ALL, keyOnly, parser), "--engine", "llm", "--input", doc.toString(), "--output", out("a"));
        assertEquals("medium", factory.settings.effort(), "회사 기본값");
        run(command(LlmProviders.ALL, withEnvironment, parser),
                "--engine", "llm", "--input", doc.toString(), "--output", out("b"));
        assertEquals("xhigh", factory.settings.effort(), "환경변수");
        Run high = run(command(LlmProviders.ALL, withEnvironment, parser),
                "--engine", "llm", "--effort", "HIGH", "--input", doc.toString(), "--output", out("c"));
        assertEquals("high", factory.settings.effort(), "옵션이 환경변수보다 먼저이고 대소문자를 가리지 않는다");
        assertTrue(high.err().contains("claude(claude-sonnet-5-5, 추론 깊이 high)로 보냅니다"), high.err());

        Run wrong = run(command(LlmProviders.ALL, keyOnly, parser),
                "--engine", "llm", "--effort", "adaptive", "--input", doc.toString(), "--output", out("d"));
        assertNotEquals(0, wrong.exit());
        assertEquals(0, factory.calls, "틀린 값이면 llm 엔진을 만들지 않는다");
        assertEquals(List.of("--effort 값 'adaptive'은 claude가 받는 추론 깊이가 아닙니다. "
                + "쓸 수 있는 값: low, medium, high, xhigh, max"), wrong.err().lines().toList());

        // Gemini 기본 모델과 gemini-4-argon은 minimal을 받지 않는다. 거절 문구에 기대지 않고 부르기 전에 막는다
        Run minimal = run(command(LlmProviders.ALL, Map.of("GEMINI_API_KEY", KEY), parser),
                "--engine", "llm", "--effort", "minimal", "--input", doc.toString(), "--output", out("e"));
        assertNotEquals(0, minimal.exit());
        assertEquals(List.of("--effort 값 'minimal'은 gemini가 받는 추론 깊이가 아닙니다. 쓸 수 있는 값: low, medium, high"),
                minimal.err().lines().toList());
    }

    @Test
    void 키_오류는_남은_파일을_변환하지_않고_실행_전체를_멈춘다() throws Exception {
        Path inputDir = Files.createDirectories(tempDir.resolve("input"));
        for (String name : List.of("a.hwp", "b.hwp", "c.hwp")) {
            Files.writeString(inputDir.resolve(name), "fake");
        }
        ParsedApi first = api("FirstApi");
        ParsedApi last = api("LastApi");
        String auth = "LLM API 키나 권한 오류로 변환하지 못했습니다 (b.hwp, claude): invalid x-api-key";
        FakeParser parser = new FakeParser(Map.of(
                "a.hwp", () -> llmResult(false, new TokenUsage(10, 0, 0, 5), first),
                "b.hwp", () -> {
                    throw new SpecParseException(SpecParseException.Kind.AUTH, auth);
                },
                "c.hwp", () -> llmResult(false, TokenUsage.NONE, last)));

        Run run = run(command(LlmProviders.ALL, Map.of("ANTHROPIC_API_KEY", KEY), parser),
                "--engine", "llm", "--input", inputDir.toString(), "--output", out("output"));

        assertEquals(1, run.exit());
        assertEquals(List.of("a.hwp", "b.hwp"), parser.parsed, "키 오류 뒤의 문서는 부르지 않는다");
        assertEquals(List.of(NOTICE, auth, "API 키나 권한 오류라 남은 파일 1개는 변환하지 않고 멈춥니다"), run.err().lines().toList());
        assertFalse(run.out().contains("개 중 "), "멈춘 실행은 변환 요약을 내지 않는다: " + run.out());
        assertTrue(Files.exists(tempDir.resolve("output/kr/go/h2spec/client/firstapi/FirstApiClient.java")));
        assertFalse(Files.exists(tempDir.resolve("output/kr/go/h2spec/client/lastapi")));

        Run single = run(command(LlmProviders.ALL, Map.of("ANTHROPIC_API_KEY", KEY), parser),
                "--engine", "llm", "--input", inputDir.resolve("b.hwp").toString(), "--output", out("single"));

        assertEquals(1, single.exit());
        assertEquals(List.of(NOTICE, auth), single.err().lines().toList());
    }

    @Test
    void 모델_이름이나_주소_오류도_남은_파일을_변환하지_않고_실행_전체를_멈춘다() throws Exception {
        // 모델 이름을 잘못 적으면 모든 문서가 같은 이유로 거절된다. 문서마다 실패로 세며 끝까지 돌지 않는다
        Path inputDir = Files.createDirectories(tempDir.resolve("input"));
        for (String name : List.of("a.hwp", "b.hwp")) {
            Files.writeString(inputDir.resolve(name), "fake");
        }
        String settings = "LLM 설정 오류로 변환하지 못했습니다. 모델 이름(--model), 추론 깊이(--effort), API 주소를 확인하세요 (a.hwp, claude): "
                + "Claude API 오류 (HTTP 404): not_found_error: model: claude-sonet-5-5";
        ParsedApi last = api("LastApi");
        FakeParser parser = new FakeParser(Map.of(
                "a.hwp", () -> {
                    throw new SpecParseException(SpecParseException.Kind.SETTINGS, settings);
                },
                "b.hwp", () -> llmResult(false, TokenUsage.NONE, last)));

        Run run = run(command(LlmProviders.ALL, Map.of("ANTHROPIC_API_KEY", KEY), parser),
                "--engine", "llm", "--input", inputDir.toString(), "--output", out("output"));

        assertEquals(1, run.exit());
        assertEquals(List.of("a.hwp"), parser.parsed);
        assertEquals(List.of(NOTICE, settings, "LLM 설정 오류라 남은 파일 1개는 변환하지 않고 멈춥니다"), run.err().lines().toList());
    }

    @Test
    void 잔액이나_사용_한도_문제도_남은_파일을_변환하지_않고_실행_전체를_멈춘다() throws Exception {
        // 잔액이 떨어지면 기다려도 풀리지 않아 남은 문서도 모두 같은 이유로 실패한다
        Path inputDir = Files.createDirectories(tempDir.resolve("input"));
        for (String name : List.of("a.hwp", "b.hwp")) {
            Files.writeString(inputDir.resolve(name), "fake");
        }
        String quota = "LLM 계정의 잔액이 없거나 사용 한도를 넘어 변환하지 못했습니다 (a.hwp, claude): "
                + "Claude API 오류 (HTTP 402): billing_error: Your credit balance is too low";
        ParsedApi last = api("LastApi");
        FakeParser parser = new FakeParser(Map.of(
                "a.hwp", () -> {
                    throw new SpecParseException(SpecParseException.Kind.QUOTA, quota);
                },
                "b.hwp", () -> llmResult(false, TokenUsage.NONE, last)));

        Run run = run(command(LlmProviders.ALL, Map.of("ANTHROPIC_API_KEY", KEY), parser),
                "--engine", "llm", "--input", inputDir.toString(), "--output", out("output"));

        assertEquals(1, run.exit());
        assertEquals(List.of("a.hwp"), parser.parsed);
        assertEquals(List.of(NOTICE, quota, "LLM 잔액·사용 한도 문제라 남은 파일 1개는 변환하지 않고 멈춥니다"),
                run.err().lines().toList());
    }

    /** 가짜 환경변수와 가짜 llm 엔진으로 convert 명령을 만든다. 실행 뒤 {@link #environment}와 {@link #factory}로 본다 */
    private ConvertCommand command(List<LlmProviderFactory> providers, Map<String, String> env, SpecParser parser) {
        environment = new RecordingEnvironment(env);
        factory = new RecordingFactory(parser);
        return new ConvertCommand(environment, providers, factory);
    }

    private Run run(ConvertCommand command, String... args) {
        return execute(new CommandLine(command), args);
    }

    /** 실제 환경변수와 회사 목록을 쓰는 h2spec. llm 엔진으로 부르면 실제 회사를 부르므로 rule 엔진과 IR JSON에만 쓴다 */
    private Run runDefault(String... args) {
        return execute(new CommandLine(new H2SpecCli()), args);
    }

    private Run execute(CommandLine cli, String... args) {
        StringWriter out = new StringWriter();
        StringWriter err = new StringWriter();
        cli.setOut(new PrintWriter(out));
        cli.setErr(new PrintWriter(err));
        int exit = cli.execute(args);
        return new Run(exit, out.toString(), err.toString());
    }

    /** 출력 디렉터리 경로를 지워 다른 디렉터리에 쓴 실행끼리 비교할 수 있게 한다 */
    private Run normalized(Run run, String outputDir) {
        return new Run(run.exit(), run.out().replace(outputDir, "<out>"), run.err().replace(outputDir, "<out>"));
    }

    private String out(String name) {
        return tempDir.resolve(name).toString();
    }

    private Path irPath() throws Exception {
        return Path.of(getClass().getResource("/ir/schema-example.json").toURI());
    }

    private Path docPath(String fileName) throws Exception {
        return Path.of(getClass().getResource("/docs/" + fileName).toURI());
    }

    /** llm 엔진이 돌려주는 것과 같은 꼴로, schema-example.json에서 apiId와 패키지만 바꾼 오퍼레이션 */
    private ParsedApi api(String apiId) throws Exception {
        ObjectNode ir = (ObjectNode) new ObjectMapper().readTree(irPath().toFile());
        ir.withObject("/api").put("apiId", apiId);
        ir.withObject("/generatorHints").put("targetPackage", "kr.go.h2spec.client." + apiId.toLowerCase(Locale.ROOT));
        return new ParsedApi(apiId, ir);
    }

    /** 모든 오퍼레이션을 변환한 llm 엔진의 결과 */
    private static SpecParseResult llmResult(boolean cacheUsed, TokenUsage tokens, ParsedApi... apis) {
        List<Operation> operations = new ArrayList<>();
        for (ParsedApi api : apis) {
            operations.add(new Operation(api.apiId(), null, api, null, false));
        }
        return new SpecParseResult(operations, List.of(),
                new Engine("llm", "claude", "claude-sonnet-5-5", "medium", cacheUsed, tokens));
    }

    private static String lineAfter(List<String> lines, String line) {
        int index = lines.indexOf(line);
        assertTrue(index >= 0 && index + 1 < lines.size(), line + " 다음 줄이 없다: " + lines);
        return lines.get(index + 1);
    }

    private static String lastLine(String text) {
        List<String> lines = text.lines().toList();
        return lines.get(lines.size() - 1);
    }

    /** 종료 코드와 표준 출력·표준 오류 */
    private record Run(int exit, String out, String err) {
    }

    /** 정해 둔 환경변수를 돌려주고 읽은 횟수를 센다 */
    private static final class RecordingEnvironment implements Supplier<Map<String, String>> {

        private final Map<String, String> values;
        private int reads;

        RecordingEnvironment(Map<String, String> values) {
            this.values = values;
        }

        @Override
        public Map<String, String> get() {
            reads++;
            return values;
        }
    }

    /** 받은 회사·설정·옵션을 기록하고 가짜 엔진을 돌려준다. 회사 클라이언트를 만들지 않으므로 네트워크를 쓰지 않는다 */
    private static final class RecordingFactory implements ConvertCommand.LlmParserFactory {

        private final SpecParser parser;
        private int calls;
        private LlmProviderFactory provider;
        private LlmSettings settings;
        private LlmSpecParser.Options options;

        RecordingFactory(SpecParser parser) {
            this.parser = parser;
        }

        @Override
        public SpecParser create(LlmProviderFactory chosen, LlmSettings settings, LlmSpecParser.Options options) {
            calls++;
            this.provider = chosen;
            this.settings = settings;
            this.options = options;
            return parser;
        }
    }

    /** 파일 이름마다 정해 둔 결과를 돌려주거나 정해 둔 예외를 던지는 엔진. 문서를 읽지 않고, 받은 파일을 순서대로 기록한다 */
    private static final class FakeParser implements SpecParser {

        private final Map<String, Outcome> outcomes;
        private final List<String> parsed = new ArrayList<>();

        FakeParser(Map<String, Outcome> outcomes) {
            this.outcomes = outcomes;
        }

        @Override
        public SpecParseResult parse(Path document) throws IOException, SpecParseException {
            String name = document.getFileName().toString();
            parsed.add(name);
            return outcomes.get(name).get();
        }
    }

    @FunctionalInterface
    private interface Outcome {

        SpecParseResult get() throws SpecParseException;
    }

    /** 회사 결정만 보는 가짜 회사. 클라이언트를 만들면 테스트가 실패한다 */
    private record FakeProvider(String id, List<String> apiKeyEnvironmentVariables) implements LlmProviderFactory {

        @Override
        public String defaultModel() {
            return id + "-default";
        }

        @Override
        public String defaultEffort() {
            return "high";
        }

        @Override
        public LlmClient create(LlmSettings settings) {
            throw new AssertionError("CLI 테스트는 회사 클라이언트를 만들지 않는다");
        }
    }
}
