package kr.go.h2spec.cli;

import org.junit.jupiter.api.Test;
import picocli.CommandLine;

import java.io.PrintWriter;
import java.io.StringWriter;

import static org.junit.jupiter.api.Assertions.*;

class H2SpecCliTest {

    @Test
    void 버전을_출력한다() {
        StringWriter out = new StringWriter();
        CommandLine cli = new CommandLine(new H2SpecCli());
        cli.setOut(new PrintWriter(out));

        int exit = cli.execute("--version");

        assertEquals(0, exit);
        assertTrue(out.toString().startsWith("h2spec "), out.toString());
        assertFalse(out.toString().isBlank());
    }

    @Test
    void 매니페스트가_없으면_개발_빌드로_표시한다() {
        // 테스트는 클래스 디렉터리에서 도므로 Implementation-Version이 없다
        String[] version = new H2SpecCli.ManifestVersion().getVersion();

        assertEquals(1, version.length);
        assertEquals("h2spec (개발 빌드)", version[0]);
    }
}
