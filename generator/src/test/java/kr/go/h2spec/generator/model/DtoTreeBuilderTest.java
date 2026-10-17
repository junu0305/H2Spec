package kr.go.h2spec.generator.model;

import kr.go.h2spec.generator.ir.ResponseField;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class DtoTreeBuilderTest {

    @Test
    void 경로를_트리로_변환한다() {
        DtoNode root = new DtoTreeBuilder().build(List.of(
                new ResponseField("response.header.resultCode", "string", "결과코드", true),
                new ResponseField("response.body.items.item[].aptNm", "string", "단지명", null),
                new ResponseField("response.body.totalCount", "integer", "전체 결과 수", null)));

        assertEquals("response", root.name());
        assertFalse(root.isLeaf());

        DtoNode resultCode = root.child("header").child("resultCode");
        assertTrue(resultCode.isLeaf());
        assertEquals("string", resultCode.leafType());
        assertEquals("결과코드", resultCode.description());

        DtoNode item = root.child("body").child("items").child("item");
        assertTrue(item.isList());
        assertFalse(item.isLeaf());
        assertEquals("string", item.child("aptNm").leafType());
    }

    @Test
    void 루트가_둘이면_실패한다() {
        List<ResponseField> fields = List.of(
                new ResponseField("response.a", "string", null, null),
                new ResponseField("header.b", "string", null, null));

        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                () -> new DtoTreeBuilder().build(fields));
        assertTrue(e.getMessage().contains("루트"));
    }

    @Test
    void 루트_없는_응답은_이름_없는_루트_아래에_맨_바깥_키를_둔다() {
        DtoNode root = new DtoTreeBuilder().build(List.of(
                new ResponseField("totCnt", "integer", "총 개수", null),
                new ResponseField("data[].baseDate", "string", "기준 일자", null),
                new ResponseField("header.resultCode", "string", "결과코드", true)), true);

        assertNull(root.name());
        assertEquals(List.of("totCnt", "data", "header"), List.copyOf(root.children().keySet()));
        assertTrue(root.child("totCnt").isLeaf());
        assertTrue(root.child("data").isList());
        assertEquals("string", root.child("data").child("baseDate").leafType());
        assertEquals("string", root.child("header").child("resultCode").leafType());
    }

    @Test
    void 루트로_감싼_응답의_루트가_리스트이거나_값이면_실패한다() {
        for (String path : List.of("data[].baseDate", "totCnt")) {
            List<ResponseField> fields = List.of(new ResponseField(path, "string", null, null));

            IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                    () -> new DtoTreeBuilder().build(fields));
            assertTrue(e.getMessage().contains("responseRootless"), e.getMessage());
        }
    }

    @Test
    void 리프와_내부노드가_충돌하면_실패한다() {
        List<ResponseField> fields = List.of(
                new ResponseField("response.a", "string", null, null),
                new ResponseField("response.a.b", "string", null, null));

        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                () -> new DtoTreeBuilder().build(fields));
        assertTrue(e.getMessage().contains("충돌"));
    }

    @Test
    void 리스트_표기_불일치하면_실패한다() {
        List<ResponseField> fields = List.of(
                new ResponseField("response.b.c", "string", null, null),
                new ResponseField("response.b[].d", "string", null, null));

        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                () -> new DtoTreeBuilder().build(fields));
        assertTrue(e.getMessage().contains("충돌"));
    }

    @Test
    void 내부노드_다음_리프가_충돌하면_실패한다() {
        List<ResponseField> fields = List.of(
                new ResponseField("response.a.b", "string", null, null),
                new ResponseField("response.a", "string", null, null));

        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                () -> new DtoTreeBuilder().build(fields));
        assertTrue(e.getMessage().contains("충돌"));
    }
}
