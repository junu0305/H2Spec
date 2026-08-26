package kr.go.h2spec.generator.emit;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** 빈 이름이 들어와도 예외 없이 빈 문자열을 돌려줘야 한다. */
class JavaNamesEdgeTest {

    @Test
    void 빈_이름은_빈_문자열을_돌려준다() {
        assertEquals("", JavaNames.camel(""));
        assertEquals("", JavaNames.pascal(""));
        assertEquals("", JavaNames.camel("   "));
        assertEquals("", JavaNames.pascal("   "));
    }
}
