package kr.go.h2spec.parser.llm;

import kr.go.h2spec.parser.Block;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Locale;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/** Block 목록을 LLM 입력 텍스트로 바꾸는 형식 규칙(설계 4.1)을 하나씩 본다. */
class DocumentSerializerTest {

    private final DocumentSerializer serializer = new DocumentSerializer();

    @Test
    void 문단과_표가_하나의_번호를_차례로_나눠_쓴다() {
        String text = serializer.serialize(List.of(
                new Block.Heading("요청 URL"),
                new Block.Table(List.of(List.of("Name", "Type"), List.of("pageNo", "integer"))),
                new Block.Heading("Response")));

        assertEquals("""
                [P1] 요청 URL
                [T2] 표 2행 2열
                | Name | Type |
                | pageNo | integer |
                [P3] Response
                """, text);
    }

    @Test
    void 표의_열_수는_가장_긴_행을_따르고_짧은_행은_채우지_않는다() {
        // 모자란 칸이 행의 어느 쪽에서 빠졌는지 모르므로 Reader가 준 칸만 적는다
        String text = serializer.serialize(List.of(new Block.Table(List.of(
                List.of("항목", "값"),
                List.of("a", "b", "c"),
                List.of("d")))));

        assertEquals("""
                [T1] 표 3행 3열
                | 항목 | 값 |
                | a | b | c |
                | d |
                """, text);
    }

    @Test
    void 칸_안의_줄바꿈과_세로줄을_바꿔_한_행을_한_줄에_둔다() {
        String text = serializer.serialize(List.of(new Block.Table(List.of(
                List.of("xml/json\ndefault : xml", "a|b", "윈도\r\n줄바꿈", "옛 맥\r줄바꿈")))));

        assertEquals("""
                [T1] 표 1행 4열
                | xml/json<br>default : xml | a\\|b | 윈도<br>줄바꿈 | 옛 맥<br>줄바꿈 |
                """, text);
    }

    @Test
    void 병합으로_비워_둔_칸은_빈_칸으로_둔다() {
        String text = serializer.serialize(List.of(new Block.Table(List.of(
                List.of("버전", "", "작성자"),
                List.of("", "", "")))));

        assertEquals("""
                [T1] 표 2행 3열
                | 버전 |  | 작성자 |
                |  |  |  |
                """, text);
    }

    @Test
    void 문단_안의_줄바꿈도_br로_바꾼다() {
        // 문단은 칸 구분자가 없어 |를 바꾸지 않는다
        assertEquals("[P1] 1줄<br>2줄 | 그대로\n",
                serializer.serialize(List.of(new Block.Heading("1줄\n2줄 | 그대로"))));
    }

    @Test
    void 목차와_개정이력도_지우지_않는다() {
        String text = serializer.serialize(List.of(
                new Block.Heading("목 차"),
                new Block.Heading("다. 요청 메시지 명세\t5"),
                new Block.Heading("개 정 이 력"),
                new Block.Table(List.of(List.of("버전", "변경일"), List.of("V 1.0", "")))));

        assertEquals("""
                [P1] 목 차
                [P2] 다. 요청 메시지 명세\t5
                [P3] 개 정 이 력
                [T4] 표 2행 2열
                | 버전 | 변경일 |
                | V 1.0 |  |
                """, text);
    }

    @Test
    void 빈_목록과_빈_표도_형식대로_적는다() {
        assertEquals("", serializer.serialize(List.of()));
        assertEquals("[T1] 표 0행 0열\n", serializer.serialize(List.of(new Block.Table(List.of()))));
    }

    @Test
    void 기본_로캘이_달라도_같은_글이_된다() {
        // 결과 캐시 키가 이 텍스트의 해시라, 실행 환경에 따라 숫자 모양이 바뀌면 같은 문서가 다른 키를 갖는다
        List<Block> blocks = List.of(new Block.Heading("제목"), new Block.Table(List.of(List.of("a", "b"))));
        String expected = serializer.serialize(blocks);
        Locale original = Locale.getDefault();
        try {
            Locale.setDefault(Locale.forLanguageTag("th-TH-u-nu-thai"));
            assertEquals(expected, new DocumentSerializer().serialize(blocks));
        } finally {
            Locale.setDefault(original);
        }
        assertEquals("[P1] 제목\n[T2] 표 1행 2열\n| a | b |\n", expected);
    }

    @Test
    void 길이_상한을_넘으면_자르지_않고_예외를_던진다() {
        List<Block> blocks = List.of(new Block.Heading("가".repeat(10)));
        // "[P1] " 5자 + 본문 10자 + 줄바꿈 1자
        assertEquals(16, new DocumentSerializer(16).serialize(blocks).length());

        DocumentTooLargeException e = assertThrows(DocumentTooLargeException.class,
                () -> new DocumentSerializer(15).serialize(blocks));

        assertEquals(16, e.length());
        assertEquals(15, e.maxLength());
    }

    @Test
    void 길이_상한은_1_이상이어야_한다() {
        assertThrows(IllegalArgumentException.class, () -> new DocumentSerializer(0));
    }
}
