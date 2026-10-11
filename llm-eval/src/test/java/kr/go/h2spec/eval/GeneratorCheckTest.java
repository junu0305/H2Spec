package kr.go.h2spec.eval;

import kr.go.h2spec.eval.GeneratorCheck.Outcome;
import kr.go.h2spec.eval.GeneratorCheck.Stage;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * generator 통과 확인: IrLoader → CodeGenerator → javac. 실패하면 예외 대신 실패한 단계와 이유를 돌려준다.
 */
class GeneratorCheckTest {

    @TempDir
    Path tempDir;

    @Test
    void 생성_코드가_컴파일되면_통과한다() throws Exception {
        Outcome outcome = new GeneratorCheck().check(IrBuilder.gateway("getStationList").build(), tempDir);

        assertEquals(Outcome.PASSED, outcome);
        try (var classes = Files.walk(tempDir.resolve("classes"))) {
            assertTrue(classes.anyMatch(path -> path.toString().endsWith("StationListClient.class")),
                    "javac가 실제로 클래스 파일을 만든다");
        }
    }

    @Test
    void 필수_필드가_없는_IR은_IrLoader에서_멈춘다() throws Exception {
        Outcome outcome = new GeneratorCheck().check(
                IrBuilder.gateway("getStationList").remove("baseUrl").build(), tempDir);

        assertFalse(outcome.passed());
        assertEquals(Stage.IR_LOADER, outcome.stage());
        assertTrue(outcome.message().contains("api.baseUrl"), outcome.message());
    }

    @Test
    void serviceKey가_없는_IR은_CodeGenerator에서_멈춘다() throws Exception {
        // corpus의 jeju 세 문서가 지금 이렇게 실패한다 (설계 1.2). IrLoader까지만 보면 놓친다
        Outcome outcome = new GeneratorCheck().check(
                new IrBuilder("JejuFolksongList", "https://www.jeju.go.kr/rest/JejuFolksongService",
                        "/getJejuFolksongList")
                        .param("authApiKey", "query", false)
                        .field("jejunetApi.items.item[].name", "string")
                        .build(), tempDir);

        assertEquals(Outcome.failed(Stage.CODE_GENERATOR, "requestParameters에 serviceKey 파라미터가 없습니다."), outcome);
    }

    @Test
    void 컴파일되지_않으면_javac_진단을_남긴다() throws Exception {
        // 생성 코드가 쓰는 spring-web·interceptor-core가 classpath에 없으면 컴파일되지 않는다
        Outcome outcome = new GeneratorCheck("").check(IrBuilder.gateway("getStationList").build(), tempDir);

        assertFalse(outcome.passed());
        assertEquals(Stage.JAVAC, outcome.stage());
        assertTrue(outcome.message().contains("error"), outcome.message());
    }
}
