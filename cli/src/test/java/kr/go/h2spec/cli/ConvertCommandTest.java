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
