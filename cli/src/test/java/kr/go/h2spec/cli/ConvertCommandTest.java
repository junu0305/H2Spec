package kr.go.h2spec.cli;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import picocli.CommandLine;

import java.io.PrintWriter;
import java.io.StringWriter;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

class ConvertCommandTest {

    @TempDir
    Path tempDir;

    @Test
    void IR_JSON을_산출물_3종으로_변환한다() throws Exception {
        int exit = new CommandLine(new H2SpecCli()).execute(
                "convert", "--input", irPath().toString(), "--output", tempDir.toString());

        assertEquals(0, exit);
        assertTrue(Files.exists(tempDir.resolve("openapi/RTMSDataSvcAptTradeDev.json")));
        assertTrue(Files.exists(tempDir.resolve(
                "kr/go/h2spec/client/landmolit/dto/RTMSDataSvcAptTradeDevResponse.java")));
        assertTrue(Files.exists(tempDir.resolve(
                "kr/go/h2spec/client/landmolit/RTMSDataSvcAptTradeDevClient.java")));
    }

    @Test
    void success_code_옵션이_성공코드를_덮어쓴다() throws Exception {
        int exit = new CommandLine(new H2SpecCli()).execute(
                "convert", "--input", irPath().toString(), "--output", tempDir.toString(),
                "--success-code", "99");

        assertEquals(0, exit);
        String client = Files.readString(tempDir.resolve(
                "kr/go/h2spec/client/landmolit/RTMSDataSvcAptTradeDevClient.java"));
        assertTrue(client.contains("SUCCESS_CODE = \"99\""));
    }

    @Test
    void format_옵션이_응답_포맷을_덮어쓴다() throws Exception {
        // 샘플 문서는 대부분 기본값이 xml이라, 이 옵션이 없으면 JSON 클라이언트를 만들 방법이 없다
        int exit = new CommandLine(new H2SpecCli()).execute(
                "convert", "--input", irPath().toString(), "--output", tempDir.toString(),
                "--format", "json");

        assertEquals(0, exit);
        String client = Files.readString(tempDir.resolve(
                "kr/go/h2spec/client/landmolit/RTMSDataSvcAptTradeDevClient.java"));
        String dto = Files.readString(tempDir.resolve(
                "kr/go/h2spec/client/landmolit/dto/RTMSDataSvcAptTradeDevResponse.java"));
        assertTrue(client.contains("objectMapper.readValue"));
        assertFalse(client.contains("XmlMapper"));
        assertFalse(dto.contains("JacksonXml"));
    }

    @Test
    void package_옵션이_생성_패키지를_덮어쓴다() throws Exception {
        int exit = new CommandLine(new H2SpecCli()).execute(
                "convert", "--input", irPath().toString(), "--output", tempDir.toString(),
                "--package", "com.example.publicdata");

        assertEquals(0, exit);
        // apiId(RTMSDataSvcAptTradeDev)를 소문자화한 하위 패키지가 자동으로 붙는다
        assertTrue(Files.exists(tempDir.resolve(
                "com/example/publicdata/rtmsdatasvcapttradedev/dto/RTMSDataSvcAptTradeDevResponse.java")));
        assertTrue(Files.exists(tempDir.resolve(
                "com/example/publicdata/rtmsdatasvcapttradedev/RTMSDataSvcAptTradeDevClient.java")));
        assertFalse(Files.exists(tempDir.resolve("kr/go/h2spec/client/landmolit")));
    }

    @Test
    void package_옵션이_DOCX_변환에도_적용되고_오퍼레이션별로_하위_패키지가_나뉜다() throws Exception {
        Path docx = Path.of(getClass().getResource("/docs/msrstn-info.docx").toURI());

        int exit = new CommandLine(new H2SpecCli()).execute(
                "convert", "--input", docx.toString(), "--output", tempDir.toString(),
                "--package", "com.example.publicdata");

        assertEquals(0, exit);
        // 측정소정보 문서의 오퍼레이션(측정소 목록 / TM 기준좌표 / 근접측정소 목록)이 각자 하위 패키지로 분리된다
        assertTrue(Files.exists(tempDir.resolve(
                "com/example/publicdata/msrstnlist/MsrstnListClient.java")));
    }

    @Test
    void format_옵션에_잘못된_값을_주면_한국어_메시지로_실패한다() throws Exception {
        StringWriter err = new StringWriter();
        CommandLine cli = new CommandLine(new H2SpecCli());
        cli.setErr(new PrintWriter(err));

        int exit = cli.execute("convert", "--input", irPath().toString(),
                "--output", tempDir.toString(), "--format", "yaml");

        assertEquals(1, exit);
        assertTrue(err.toString().contains("xml"), err.toString());
    }

    @Test
    void DOCX_명세서를_오퍼레이션별_산출물로_변환한다() throws Exception {
        Path docx = Path.of(getClass().getResource("/docs/msrstn-info.docx").toURI());

        int exit = new CommandLine(new H2SpecCli()).execute(
                "convert", "--input", docx.toString(), "--output", tempDir.toString());

        assertEquals(0, exit);
        assertTrue(Files.exists(tempDir.resolve("ir/MsrstnList.json")));
        assertTrue(Files.exists(tempDir.resolve("openapi/MsrstnList.json")));
        assertTrue(Files.exists(tempDir.resolve(
                "kr/go/h2spec/client/msrstnlist/MsrstnListClient.java")));
    }

    @Test
    void 디렉터리를_입력하면_안의_명세_파일을_전부_변환한다() throws Exception {
        Path inputDir = Files.createDirectories(tempDir.resolve("input"));
        Files.copy(irPath(), inputDir.resolve("schema-example.json"));
        Files.copy(Path.of(getClass().getResource("/docs/msrstn-info.docx").toURI()),
                inputDir.resolve("msrstn-info.docx"));
        // 명세 파일이 아닌 파일은 무시되어야 한다
        Files.writeString(inputDir.resolve("README.md"), "관련 없는 파일");
        Path outputDir = tempDir.resolve("output");

        int exit = new CommandLine(new H2SpecCli()).execute(
                "convert", "--input", inputDir.toString(), "--output", outputDir.toString());

        assertEquals(0, exit);
        // JSON IR 한 건
        assertTrue(Files.exists(outputDir.resolve(
                "kr/go/h2spec/client/landmolit/RTMSDataSvcAptTradeDevClient.java")));
        // DOCX(오퍼레이션 여러 개 포함) 한 건
        assertTrue(Files.exists(outputDir.resolve(
                "kr/go/h2spec/client/msrstnlist/MsrstnListClient.java")));
    }

    @Test
    void 디렉터리_입력에도_package_옵션이_모든_파일에_적용된다() throws Exception {
        Path inputDir = Files.createDirectories(tempDir.resolve("input"));
        Files.copy(irPath(), inputDir.resolve("schema-example.json"));
        Files.copy(Path.of(getClass().getResource("/docs/msrstn-info.docx").toURI()),
                inputDir.resolve("msrstn-info.docx"));
        Path outputDir = tempDir.resolve("output");

        int exit = new CommandLine(new H2SpecCli()).execute(
                "convert", "--input", inputDir.toString(), "--output", outputDir.toString(),
                "--package", "com.example.publicdata");

        assertEquals(0, exit);
        assertTrue(Files.exists(outputDir.resolve(
                "com/example/publicdata/rtmsdatasvcapttradedev/RTMSDataSvcAptTradeDevClient.java")));
        assertTrue(Files.exists(outputDir.resolve(
                "com/example/publicdata/msrstnlist/MsrstnListClient.java")));
    }

    @Test
    void 변환할_명세_파일이_없는_디렉터리는_한국어_메시지로_실패한다() throws Exception {
        Path inputDir = Files.createDirectories(tempDir.resolve("empty-input"));
        Files.writeString(inputDir.resolve("readme.txt"), "명세 파일 아님");
        StringWriter err = new StringWriter();
        CommandLine cli = new CommandLine(new H2SpecCli());
        cli.setErr(new PrintWriter(err));

        int exit = cli.execute("convert", "--input", inputDir.toString(),
                "--output", tempDir.resolve("output").toString());

        assertEquals(1, exit);
        assertTrue(err.toString().contains("찾지 못했습니다"), err.toString());
    }

    @Test
    void 디렉터리_안에_손상된_파일이_있어도_나머지_파일은_변환되고_결과는_실패로_보고된다() throws Exception {
        Path inputDir = Files.createDirectories(tempDir.resolve("input"));
        Files.copy(irPath(), inputDir.resolve("schema-example.json"));
        Files.writeString(inputDir.resolve("broken.json"), "{ not valid json");
        Path outputDir = tempDir.resolve("output");

        int exit = new CommandLine(new H2SpecCli()).execute(
                "convert", "--input", inputDir.toString(), "--output", outputDir.toString());

        assertEquals(1, exit, "일부 파일이 실패하면 디렉터리 변환 전체 종료 코드는 1이어야 한다");
        assertTrue(Files.exists(outputDir.resolve(
                "kr/go/h2spec/client/landmolit/RTMSDataSvcAptTradeDevClient.java")),
                "실패한 파일이 있어도 나머지 파일은 계속 변환되어야 한다");
    }

    @Test
    void 같은_apiId를_만드는_문서가_겹치면_경고한다() throws Exception {
        // 배치는 모든 문서를 한 출력 디렉터리에 쏟으므로 apiId가 겹치면 앞선 산출물이 사라진다
        Path inputDir = Files.createDirectories(tempDir.resolve("dup-input"));
        Files.copy(irPath(), inputDir.resolve("a.json"));
        Files.copy(irPath(), inputDir.resolve("b.json"));
        StringWriter err = new StringWriter();
        CommandLine cli = new CommandLine(new H2SpecCli());
        cli.setErr(new PrintWriter(err));

        int exit = cli.execute("convert", "--input", inputDir.toString(),
                "--output", tempDir.resolve("dup-output").toString());

        assertEquals(0, exit);
        assertTrue(err.toString().contains("RTMSDataSvcAptTradeDev"),
                "덮어쓴 apiId를 알려야 한다: " + err);
    }

    @Test
    void HWP만_있는_디렉터리는_HWP_미지원을_안내한다() throws Exception {
        // 단일 파일 입력과 달리 디렉터리에서는 .hwp가 조용히 걸러져 이유를 알 수 없었다
        Path inputDir = Files.createDirectories(tempDir.resolve("hwp-input"));
        Files.writeString(inputDir.resolve("명세서.hwp"), "dummy");
        StringWriter err = new StringWriter();
        CommandLine cli = new CommandLine(new H2SpecCli());
        cli.setErr(new PrintWriter(err));

        int exit = cli.execute("convert", "--input", inputDir.toString(),
                "--output", tempDir.resolve("hwp-output").toString());

        assertEquals(1, exit);
        assertTrue(err.toString().contains("HWP"), err.toString());
    }

    @Test
    void HWP_입력은_아직_지원하지_않는다고_안내한다() throws Exception {
        Path hwp = tempDir.resolve("명세서.hwp");
        Files.writeString(hwp, "dummy");
        StringWriter err = new StringWriter();
        CommandLine cmd = new CommandLine(new H2SpecCli());
        cmd.setErr(new PrintWriter(err));

        int exit = cmd.execute("convert", "--input", hwp.toString(), "--output", tempDir.toString());

        assertEquals(1, exit);
        assertTrue(err.toString().contains("HWP"));
    }

    @Test
    void 없는_입력_파일은_한국어_메시지로_실패한다() {
        StringWriter err = new StringWriter();
        CommandLine cmd = new CommandLine(new H2SpecCli());
        cmd.setErr(new PrintWriter(err));

        int exit = cmd.execute("convert", "--input", "없는파일.json", "--output", tempDir.toString());

        assertEquals(1, exit);
        assertTrue(err.toString().contains("입력 파일"));
    }

    private Path irPath() throws Exception {
        return Path.of(getClass().getResource("/ir/schema-example.json").toURI());
    }
}
