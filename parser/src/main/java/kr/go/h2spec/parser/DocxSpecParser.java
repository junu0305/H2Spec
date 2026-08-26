package kr.go.h2spec.parser;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;

/**
 * 공공데이터포털 표준 기술문서(DOCX)를 읽어 상세기능(오퍼레이션)별 IR JSON을 추출한다.
 * <p>
 * 문서 구조 가정: "a) 상세기능정보" → 키/값 표(Call Back URL 포함),
 * "b) 요청 메시지 명세" → 파라미터 표, "c) 응답 메시지 명세" → 응답 필드 표가
 * 오퍼레이션마다 반복된다.
 */
public class DocxSpecParser {

    public List<ParsedApi> parse(Path docx) throws IOException {
        List<Block> blocks = new DocxSpecReader().read(docx);
        List<ParsedApi> result = new SpecBlockAssembler().parse(blocks, docx.getFileName().toString(), "DOCX");

        if (result.isEmpty()) {
            throw new IllegalArgumentException("문서에서 상세기능 명세를 찾지 못했습니다: " + docx.getFileName());
        }
        return result;
    }
}
