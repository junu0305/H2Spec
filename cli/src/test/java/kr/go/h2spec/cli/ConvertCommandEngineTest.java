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
import kr.go.h2spec.parser.llm.ExtractionCache;
import kr.go.h2spec.parser.llm.LlmSpecParser;
import kr.go.h2spec.parser.llm.client.LlmClient;
import kr.go.h2spec.parser.llm.client.LlmProviderFactory;
import kr.go.h2spec.parser.llm.client.LlmProviders;
import kr.go.h2spec.parser.llm.client.LlmSettings;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import picocli.CommandLine;

import java.io.IOException;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.function.Supplier;

import static org.junit.jupiter.api.Assertions.*;

/**
 * convert의 엔진 옵션 (설계 7.1~7.4, 1-8). 환경변수와 llm 엔진을 가짜로 바꿔 실제 환경변수·키·네트워크 없이 본다.
 * 가짜 엔진은 문서를 읽지 않고 파일 이름마다 정해 둔 결과를 돌려주므로, 명세 문서는 확장자만 맞춘 빈 파일로 둔다.
 */
class ConvertCommandEngineTest {

    private static final String KEY = "sk-ant-api03-cli-test-secret";
    private static final String NOTICE = "문서 내용을 claude(claude-opus-5-5)로 보냅니다. 비공개 문서는 --engine rule로 변환하세요.";
    private static final LlmProviderFactory CLAUDE = new FakeProvider("claude", List.of("ANTHROPIC_API_KEY"));
    private static final LlmProviderFactory OPENAI = new FakeProvider("openai", List.of("OPENAI_API_KEY"));
    /** 쓰이면 안 되는 엔진. 회사를 정하지 못한 실행이나 rule 엔진 실행에서 쓴다 */
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
                "H2SPEC_LLM_PROVIDER", "claude", "H2SPEC_LLM_MODEL", "claude-opus-5-5");

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
    void llm_엔진은_키가_없으면_파일을_하나도_변환하지_않고_정해진_문구로_실패한다() throws Exception {
        Path inputDir = Files.createDirectories(tempDir.resolve("input"));
        Files.copy(irPath(), inputDir.resolve("a.json"));
        Files.writeString(inputDir.resolve("b.hwp"), "fake");

        Run run = run(command(LlmProviders.ALL, Map.of("OPENAI_API_KEY", "o-key"), UNUSED),
                "--engine", "llm", "--input", inputDir.toString(), "--output", out("output"));

        assertEquals(1, run.exit());
        assertEquals("LLM API 키가 없습니다. 쓸 회사의 키를 환경변수(ANTHROPIC_API_KEY)로 설정하세요" + System.lineSeparator(),
                run.err());
        assertEquals("", run.out(), "회사를 정하기 전에는 어떤 파일도 변환하지 않는다");
        assertFalse(Files.exists(tempDir.resolve("output")), "앞에 있는 IR JSON도 생성하지 않는다");
        assertEquals(0, factory.calls);
    }

    @Test
    void llm_엔진은_키가_여러_회사_것이면_정해진_문구로_실패한다() throws Exception {
        Path doc = Files.writeString(tempDir.resolve("a.hwp"), "fake");

        Run run = run(command(List.of(CLAUDE, OPENAI), Map.of("ANTHROPIC_API_KEY", KEY, "OPENAI_API_KEY", "o-key"), UNUSED),
                "--engine", "llm", "--input", doc.toString(), "--output", out("output"));

        assertEquals(1, run.exit());
        assertEquals("LLM 키가 여러 개입니다(claude, openai). --provider 또는 H2SPEC_LLM_PROVIDER로 지정하세요"
                + System.lineSeparator(), run.err());
        assertEquals("", run.out());
        assertEquals(0, factory.calls);
    }

    @Test
    void 회사는_provider_옵션이나_H2SPEC_LLM_PROVIDER로_정하고_그_회사의_키를_쓴다() throws Exception {
        Path doc = Files.writeString(tempDir.resolve("a.hwp"), "fake");
        SpecParser parser = fixed(llmResult(false, TokenUsage.NONE, api("FirstApi")));
        Map<String, String> keys = Map.of("ANTHROPIC_API_KEY", KEY, "OPENAI_API_KEY", " o-key ");

        Run fromOption = run(command(List.of(CLAUDE, OPENAI), keys, parser),
                "--engine", "llm", "--provider", "openai", "--input", doc.toString(), "--output", out("option"));
        LlmSettings optionSettings = factory.settings;
        LlmProviderFactory optionProvider = factory.provider;
        Map<String, String> withProvider = new HashMap<>(keys);
        withProvider.put("H2SPEC_LLM_PROVIDER", "openai");
        Run fromEnvironment = run(command(List.of(CLAUDE, OPENAI), withProvider, parser),
                "--engine", "llm", "--input", doc.toString(), "--output", out("environment"));

        assertEquals(0, fromOption.exit(), fromOption.err());
        assertEquals(0, fromEnvironment.exit(), fromEnvironment.err());
        assertSame(OPENAI, optionProvider);
        assertSame(OPENAI, factory.provider);
        assertEquals("o-key", optionSettings.apiKey(), "키는 앞뒤 공백을 뗀 환경변수 값이다");
        assertEquals("o-key", factory.settings.apiKey());
    }

    @Test
    void 모델은_model_옵션_H2SPEC_LLM_MODEL_회사_기본_모델_순으로_정하고_추론_깊이는_회사_기본값을_쓴다() throws Exception {
        Path doc = Files.writeString(tempDir.resolve("a.hwp"), "fake");
        SpecParser parser = fixed(llmResult(false, TokenUsage.NONE, api("FirstApi")));
        Map<String, String> withModel = Map.of("ANTHROPIC_API_KEY", KEY, "H2SPEC_LLM_MODEL", " env-model ");

        run(command(List.of(CLAUDE), withModel, parser),
                "--engine", "llm", "--model", "option-model", "--input", doc.toString(), "--output", out("option"));
        LlmSettings fromOption = factory.settings;
        run(command(List.of(CLAUDE), withModel, parser),
                "--engine", "llm", "--input", doc.toString(), "--output", out("environment"));
        LlmSettings fromEnvironment = factory.settings;
        Run byDefault = run(command(List.of(CLAUDE), Map.of("ANTHROPIC_API_KEY", KEY, "H2SPEC_LLM_MODEL", " "), parser),
                "--engine", "llm", "--input", doc.toString(), "--output", out("default"));

        assertEquals("option-model", fromOption.model());
        assertEquals("env-model", fromEnvironment.model(), "앞뒤 공백은 뗀다");
        assertEquals("claude-default", factory.settings.model(), "빈 환경변수는 설정하지 않은 것으로 본다");
        for (LlmSettings settings : List.of(fromOption, fromEnvironment, factory.settings)) {
            assertEquals("high", settings.effort());
            assertEquals(KEY, settings.apiKey());
        }
        assertTrue(byDefault.err().contains("문서 내용을 claude(claude-default)로 보냅니다."), byDefault.err());
    }

    @Test
    void auto_엔진은_아직_지원하지_않는다고_알리고_회사를_정하지_않는다() throws Exception {
        Path doc = Files.writeString(tempDir.resolve("a.hwp"), "fake");

        Run auto = run(command(LlmProviders.ALL, Map.of("ANTHROPIC_API_KEY", KEY, "H2SPEC_LLM_PROVIDER", "claude"), UNUSED),
                "--engine", "auto", "--input", doc.toString(), "--output", out("output"));

        assertEquals(1, auto.exit());
        assertEquals("--engine auto는 아직 지원하지 않습니다. rule 또는 llm을 쓰세요" + System.lineSeparator(), auto.err());
        assertEquals(0, environment.reads);
        assertEquals(0, factory.calls);

        Run unknown = run(command(LlmProviders.ALL, Map.of(), UNUSED),
                "--engine", "gpt", "--input", doc.toString(), "--output", out("output"));

        assertEquals(1, unknown.exit());
        assertEquals("--engine 값은 rule 또는 llm 이어야 합니다: gpt" + System.lineSeparator(), unknown.err());
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
    void 키에_보낼_수_없는_글자가_있으면_키를_드러내지_않고_실패한다() throws Exception {
        Path doc = Files.writeString(tempDir.resolve("a.hwp"), "fake");
        String brokenKey = "sk-ant-api03 broken-secret";

        Run run = run(command(LlmProviders.ALL, Map.of("ANTHROPIC_API_KEY", brokenKey), UNUSED),
                "--engine", "llm", "--input", doc.toString(), "--output", out("output"));

        assertEquals(1, run.exit());
        assertEquals("API 키가 비었거나 공백·제어 문자·ASCII 밖의 글자가 들어 있습니다" + System.lineSeparator(), run.err());
        assertFalse(run.err().contains("broken-secret"));
        assertEquals(0, factory.calls);
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
        assertEquals("엔진: llm (claude / claude-opus-5-5), 캐시 없음, 토큰 입력 1230 / 출력 400 (thinking 포함)",
                lineAfter(lines, "=== a.hwp ==="), "입력은 캐시에서 읽고 쓴 입력까지 더한 보낸 입력 전체다");
        assertEquals("엔진: llm (claude / claude-opus-5-5), 캐시 없음, 토큰 입력 18 / 출력 8 (thinking 포함)",
                lineAfter(lines, "=== b.docx ==="));
        assertEquals("엔진: llm (claude / claude-opus-5-5), 캐시 사용, 토큰 입력 0 / 출력 0 (thinking 포함)",
                lineAfter(lines, "=== c.hwpx ==="));
        assertTrue(lineAfter(lines, "=== d.json ===").startsWith("생성: "), "IR JSON은 엔진을 쓰지 않는다: " + run.out());
        assertEquals(3, lines.stream().filter(line -> line.startsWith("엔진: ")).count(), run.out());
        assertEquals("4개 중 4개 변환 성공, 0개 실패 · LLM 토큰 합계 입력 1248 / 출력 408", lastLine(run.out()));
    }

    @Test
    void 파일_하나를_llm_엔진으로_변환하면_엔진_줄을_내고_건너뛴_오퍼레이션은_기존_형식으로_알린다() throws Exception {
        Path doc = Files.writeString(tempDir.resolve("wifi.hwp"), "fake");
        ParsedApi first = api("FirstApi");
        String skipped = "읍면동별 와이파이 사용량 API: 인증 방식 미지원 (주소 경로의 {your_appkey})";
        SpecParseResult result = new SpecParseResult(
                List.of(new Operation("FirstApi", null, first, null, false),
                        new Operation(null, "읍면동별 와이파이 사용량 API", null, "인증 방식 미지원 (주소 경로의 {your_appkey})", true)),
                List.of(skipped),
                new Engine("llm", "claude", "claude-opus-5-5", "medium", false, new TokenUsage(100, 0, 0, 50)));

        Run run = run(command(LlmProviders.ALL, Map.of("ANTHROPIC_API_KEY", KEY), fixed(result)),
                "--engine", "llm", "--input", doc.toString(), "--output", out("output"));

        assertEquals(0, run.exit(), run.err());
        assertEquals("엔진: llm (claude / claude-opus-5-5), 캐시 없음, 토큰 입력 100 / 출력 50 (thinking 포함)",
                run.out().lines().findFirst().orElseThrow());
        assertFalse(run.out().contains("개 중 "), "파일 하나는 요약이 없다: " + run.out());
        assertEquals(List.of(NOTICE, "건너뛴 오퍼레이션 (wifi.hwp): " + skipped), run.err().lines().toList());
        assertTrue(Files.exists(tempDir.resolve("output/kr/go/h2spec/client/firstapi/FirstApiClient.java")));
    }

    @Test
    void 키_오류는_남은_파일을_변환하지_않고_실행_전체를_멈춘다() throws Exception {
        Path inputDir = Files.createDirectories(tempDir.resolve("input"));
        for (String name : List.of("a.hwp", "b.hwp", "c.hwp")) {
            Files.writeString(inputDir.resolve(name), "fake");
        }
        ParsedApi first = api("FirstApi");
        ParsedApi last = api("LastApi");
        String auth = "LLM API 키 오류로 변환하지 못했습니다 (b.hwp, claude): invalid x-api-key";
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
        assertEquals(List.of(NOTICE, auth, "API 키 오류라 남은 파일 1개는 변환하지 않고 멈춥니다"), run.err().lines().toList());
        assertFalse(run.out().contains("개 중 "), "멈춘 실행은 변환 요약을 내지 않는다: " + run.out());
        assertTrue(Files.exists(tempDir.resolve("output/kr/go/h2spec/client/firstapi/FirstApiClient.java")));
        assertFalse(Files.exists(tempDir.resolve("output/kr/go/h2spec/client/lastapi")));

        Run single = run(command(LlmProviders.ALL, Map.of("ANTHROPIC_API_KEY", KEY), parser),
                "--engine", "llm", "--input", inputDir.resolve("b.hwp").toString(), "--output", out("single"));

        assertEquals(1, single.exit());
        assertEquals(List.of(NOTICE, auth), single.err().lines().toList());
    }

    @ParameterizedTest
    @EnumSource(value = SpecParseException.Kind.class, names = {"RETRYABLE", "FAILED"})
    void 키_오류가_아닌_실패는_그_문서만_실패로_세고_나머지_문서는_계속_변환한다(SpecParseException.Kind kind) throws Exception {
        Path inputDir = Files.createDirectories(tempDir.resolve("input"));
        Files.writeString(inputDir.resolve("a.hwp"), "fake");
        Files.writeString(inputDir.resolve("b.hwp"), "fake");
        ParsedApi last = api("LastApi");
        String failure = "LLM 서버나 네트워크 오류로 변환하지 못했습니다. 잠시 뒤 다시 실행하세요 (a.hwp, claude)";
        FakeParser parser = new FakeParser(Map.of(
                "a.hwp", () -> {
                    throw new SpecParseException(kind, failure);
                },
                "b.hwp", () -> llmResult(false, new TokenUsage(100, 20, 3, 40), last)));

        Run run = run(command(LlmProviders.ALL, Map.of("ANTHROPIC_API_KEY", KEY), parser),
                "--engine", "llm", "--input", inputDir.toString(), "--output", out("output"));

        assertEquals(1, run.exit(), "실패한 문서가 있으면 1이다");
        assertEquals(List.of("a.hwp", "b.hwp"), parser.parsed);
        assertEquals(List.of(NOTICE, failure), run.err().lines().toList());
        assertEquals("2개 중 1개 변환 성공, 1개 실패 · LLM 토큰 합계 입력 123 / 출력 40", lastLine(run.out()));
        assertTrue(Files.exists(tempDir.resolve("output/kr/go/h2spec/client/lastapi/LastApiClient.java")));
    }

    @Test
    void LLM이_답한_뒤에_실패한_문서도_엔진과_토큰을_알리고_합계에_더한다() throws Exception {
        Path inputDir = Files.createDirectories(tempDir.resolve("input"));
        Files.writeString(inputDir.resolve("a.hwp"), "fake");
        Files.writeString(inputDir.resolve("b.hwp"), "fake");
        ParsedApi last = api("LastApi");
        String truncated = "LLM 출력이 출력 한도 64000 토큰에 걸려 잘렸습니다 (a.hwp, 출력 64000 토큰, thinking 포함)";
        FakeParser parser = new FakeParser(Map.of(
                "a.hwp", () -> {
                    throw new SpecParseException(SpecParseException.Kind.FAILED, truncated, null,
                            new Engine("llm", "claude", "claude-opus-5-5", "medium", false, new TokenUsage(1000, 0, 0, 64000)));
                },
                "b.hwp", () -> llmResult(false, new TokenUsage(100, 20, 3, 40), last)));

        Run run = run(command(LlmProviders.ALL, Map.of("ANTHROPIC_API_KEY", KEY), parser),
                "--engine", "llm", "--input", inputDir.toString(), "--output", out("output"));

        assertEquals(1, run.exit());
        assertEquals("엔진: llm (claude / claude-opus-5-5), 캐시 없음, 토큰 입력 1000 / 출력 64000 (thinking 포함)",
                lineAfter(run.out().lines().toList(), "=== a.hwp ==="), "잘린 출력도 과금되므로 알린다");
        assertEquals(List.of(NOTICE, truncated), run.err().lines().toList());
        assertEquals("2개 중 1개 변환 성공, 1개 실패 · LLM 토큰 합계 입력 1123 / 출력 64040", lastLine(run.out()));
    }

    @Test
    void 키_오류로_멈출_때도_앞서_답을_받은_호출이_있으면_그_토큰을_알린다() throws Exception {
        Path doc = Files.writeString(tempDir.resolve("a.hwp"), "fake");
        String auth = "LLM API 키 오류로 변환하지 못했습니다 (a.hwp, claude): invalid x-api-key";
        SpecParser parser = document -> {
            // JSON을 읽지 못해 다시 부른 호출에서 키 오류가 난 경우다. 첫 호출은 이미 과금됐다
            throw new SpecParseException(SpecParseException.Kind.AUTH, auth, null,
                    new Engine("llm", "claude", "claude-opus-5-5", "medium", false, new TokenUsage(10, 0, 0, 5)));
        };

        Run run = run(command(LlmProviders.ALL, Map.of("ANTHROPIC_API_KEY", KEY), parser),
                "--engine", "llm", "--input", doc.toString(), "--output", out("output"));

        assertEquals(1, run.exit());
        assertEquals("엔진: llm (claude / claude-opus-5-5), 캐시 없음, 토큰 입력 10 / 출력 5 (thinking 포함)",
                run.out().lines().findFirst().orElseThrow());
        assertEquals(List.of(NOTICE, auth), run.err().lines().toList());
    }

    @Test
    void 캐시와_교차검증_옵션을_llm_엔진_설정에_옮긴다() throws Exception {
        Path doc = Files.writeString(tempDir.resolve("a.hwp"), "fake");
        Path cacheDir = tempDir.resolve("cache");
        SpecParser parser = fixed(llmResult(false, TokenUsage.NONE, api("FirstApi")));
        Map<String, String> keys = Map.of("ANTHROPIC_API_KEY", KEY);

        run(command(LlmProviders.ALL, keys, parser), "--engine", "llm", "--input", doc.toString(), "--output", out("default"));
        LlmSpecParser.Options defaults = factory.options;
        run(command(LlmProviders.ALL, keys, parser), "--engine", "llm", "--cache-dir", cacheDir.toString(),
                "--input", doc.toString(), "--output", out("cache-dir"));
        LlmSpecParser.Options withCacheDir = factory.options;
        run(command(LlmProviders.ALL, keys, parser), "--engine", "llm", "--no-cache", "--no-cross-check",
                "--input", doc.toString(), "--output", out("no-cache"));
        LlmSpecParser.Options withoutCache = factory.options;
        run(command(LlmProviders.ALL, keys, parser), "--engine", "llm", "--no-cache", "--cache-dir", cacheDir.toString(),
                "--input", doc.toString(), "--output", out("both"));
        LlmSpecParser.Options both = factory.options;

        assertEquals(Optional.of(ExtractionCache.defaultDirectory()), defaults.cache().directory());
        assertTrue(defaults.crossCheck());
        assertEquals(LlmSpecParser.DEFAULT_MAX_OUTPUT_TOKENS, defaults.maxOutputTokens());
        assertEquals(Optional.of(cacheDir), withCacheDir.cache().directory());
        assertTrue(withCacheDir.crossCheck());
        assertFalse(withoutCache.cache().enabled());
        assertFalse(withoutCache.crossCheck());
        assertFalse(both.cache().enabled(), "--no-cache는 --cache-dir과 함께 와도 캐시를 끈다");
        assertFalse(Files.exists(cacheDir), "가짜 엔진은 캐시를 쓰지 않는다");
    }

    @Test
    void llm_엔진의_경고는_표준_오류로_알린다() throws Exception {
        Path doc = Files.writeString(tempDir.resolve("a.hwp"), "fake");
        SpecParseResult result = llmResult(false, TokenUsage.NONE, api("FirstApi"));
        String warning = "a.hwp: rule 엔진만 찾은 오퍼레이션: 통계 (http://apis.data.go.kr/B000000/svc/getStats)";
        SpecParser parser = document -> {
            factory.options.warnings().accept(warning);
            return result;
        };

        Run run = run(command(LlmProviders.ALL, Map.of("ANTHROPIC_API_KEY", KEY), parser),
                "--engine", "llm", "--input", doc.toString(), "--output", out("output"));

        assertEquals(0, run.exit(), run.err());
        assertEquals(List.of(NOTICE, warning), run.err().lines().toList());
    }

    @Test
    void IR_JSON_입력은_llm_엔진에서도_회사를_정하지_않고_rule_엔진과_같게_변환한다() throws Exception {
        Path inputDir = Files.createDirectories(tempDir.resolve("input"));
        Files.copy(irPath(), inputDir.resolve("schema-example.json"));
        Files.copy(Path.of(getClass().getResource("/ir/path-parameter-example.json").toURI()),
                inputDir.resolve("path-parameter-example.json"));

        for (Path input : List.of(inputDir.resolve("schema-example.json"), inputDir)) {
            String ruleOutput = out(input.getFileName() + "-rule");
            String llmOutput = out(input.getFileName() + "-llm");
            Run expected = normalized(runDefault("convert", "--input", input.toString(), "--output", ruleOutput), ruleOutput);

            // 키가 없어도 실패하지 않는다. IR JSON은 LLM에 보내지 않으므로 회사도 키도 필요 없다
            Run actual = normalized(run(command(LlmProviders.ALL, Map.of(), UNUSED),
                    "--engine", "llm", "--input", input.toString(), "--output", llmOutput), llmOutput);

            assertEquals(expected, actual, input.toString());
            assertEquals(0, actual.exit(), actual.err());
            assertEquals(0, environment.reads, "IR JSON만 있으면 환경변수를 읽지 않는다");
            assertEquals(0, factory.calls);
        }
        Run directory = run(command(LlmProviders.ALL, Map.of(), UNUSED),
                "--engine", "llm", "--input", inputDir.toString(), "--output", out("summary"));
        assertEquals("2개 중 2개 변환 성공, 0개 실패", lastLine(directory.out()), "LLM을 부르지 않은 실행에는 토큰 합계가 없다");
        assertFalse(directory.err().contains("문서 내용을"), "보낼 문서가 없으면 안내하지 않는다");
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
                new Engine("llm", "claude", "claude-opus-5-5", "medium", cacheUsed, tokens));
    }

    /** 어느 문서든 같은 결과를 돌려주는 엔진 */
    private static SpecParser fixed(SpecParseResult result) {
        return document -> result;
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

    /** 1단계에는 Claude뿐이라 아직 없는 회사까지 흉내 낸다. 클라이언트를 만들면 테스트가 실패한다 */
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
