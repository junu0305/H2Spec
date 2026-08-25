package kr.go.h2spec.generator;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** OS별 줄바꿈 차이와 무관하게 생성 결과를 골든파일과 비교하는 테스트 유틸리티. */
public final class GoldenFileAssertions {
    private GoldenFileAssertions() {}

    public static void assertEqualsIgnoringLineEndings(String expected, String actual) {
        assertEquals(normalizeLineEndings(expected), normalizeLineEndings(actual));
    }

    private static String normalizeLineEndings(String value) {
        return value.replace("\r\n", "\n").replace('\r', '\n');
    }
}
