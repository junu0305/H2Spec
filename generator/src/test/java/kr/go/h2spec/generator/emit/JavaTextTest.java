package kr.go.h2spec.generator.emit;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class JavaTextTest {

    @Test
    void 리터럴은_역슬래시를_먼저_두_겹으로_만든다() {
        // 역슬래시가 하나로 남으면 javac가 유니코드 escape로 풀어 따옴표가 된다
        assertEquals("a\\\\b", JavaText.literal("a\\b"));
        assertEquals("\\\\u0022", JavaText.literal("\\u0022"));
    }

    @Test
    void 리터럴은_따옴표와_제어문자를_escape한다() {
        assertEquals("00\\\"; static { System.exit(1); } //", JavaText.literal("00\"; static { System.exit(1); } //"));
        assertEquals("a\\nb\\r\\tc", JavaText.literal("a\nb\r\tc"));
        // 8진 escape는 세 자리로 써서 뒤의 숫자 1이 escape에 붙지 않는다
        assertEquals("a\\000b\\1771", JavaText.literal("a\0b\u007f1"));
    }

    @Test
    void 주석은_닫는_기호와_역슬래시를_무력화한다() {
        assertEquals("설명 *&#47; class X {} /*", JavaText.comment("설명 */ class X {} /*"));
        assertEquals("설명 &#92;u002a&#92;u002f class X {}", JavaText.comment("설명 \\u002a\\u002f class X {}"));
    }

    @Test
    void 주석은_줄바꿈을_공백으로_바꾼다() {
        assertEquals("첫 줄 둘째 줄 셋째 줄", JavaText.comment("첫 줄\r\n둘째 줄\n셋째 줄"));
    }

    @Test
    void 한글은_escape하지_않는다() {
        // 생성 코드를 그대로 읽을 수 있고 golden 파일이 바뀌지 않는다
        assertEquals("거래금액 (만원, 콤마 포함 문자열 주의)", JavaText.literal("거래금액 (만원, 콤마 포함 문자열 주의)"));
        assertEquals("전용면적(㎡)", JavaText.comment("전용면적(㎡)"));
    }

    @Test
    void null은_지금처럼_null로_쓴다() {
        assertEquals("null", JavaText.literal(null));
        assertEquals("null", JavaText.comment(null));
    }
}
