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
