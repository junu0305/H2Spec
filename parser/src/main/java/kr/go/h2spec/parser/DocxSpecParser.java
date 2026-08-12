package kr.go.h2spec.parser;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 공공데이터포털 표준 기술문서(DOCX)를 읽어 상세기능(오퍼레이션)별 IR JSON을 추출한다.
 * <p>
 * 문서 구조 가정: "a) 상세기능정보" → 키/값 표(Call Back URL 포함),
 * "b) 요청 메시지 명세" → 파라미터 표, "c) 응답 메시지 명세" → 응답 필드 표가
 * 오퍼레이션마다 반복된다.
 */
public class DocxSpecParser {

    private enum Pending {
        INFO, REQUEST, RESPONSE
    }

    public List<ParsedApi> parse(Path docx) throws IOException {
        List<Block> blocks = new DocxSpecReader().read(docx);
        IrAssembler assembler = new IrAssembler();
        List<ParsedApi> result = new ArrayList<>();

        Map<String, String> info = null;
        List<List<String>> requestRows = null;
        Pending pending = null;

        for (Block block : blocks) {
            if (block instanceof Block.Heading heading) {
                Pending next = pendingFor(heading.text());
                if (next != null) {
                    pending = next;
                }
            } else if (block instanceof Block.Table table && pending != null) {
                switch (pending) {
                    case INFO -> {
                        info = toKeyValue(table.rows());
                        requestRows = null;
                    }
                    case REQUEST -> requestRows = table.rows();
                    case RESPONSE -> {
                        if (info != null && requestRows != null) {
                            result.add(assembler.assemble(
                                    docx.getFileName().toString(), info, requestRows, table.rows()));
                        }
                        info = null;
                        requestRows = null;
                    }
                }
                pending = null;
            }
        }

        if (result.isEmpty()) {
            throw new IllegalArgumentException("문서에서 상세기능 명세를 찾지 못했습니다: " + docx.getFileName());
        }
        return result;
    }

    private Pending pendingFor(String text) {
        if (text.contains("상세기능정보")) {
            return Pending.INFO;
        }
        if (text.contains("요청 메시지 명세")) {
            return Pending.REQUEST;
        }
        if (text.contains("응답 메시지 명세")) {
            return Pending.RESPONSE;
        }
        return null;
    }

    private Map<String, String> toKeyValue(List<List<String>> rows) {
        Map<String, String> info = new LinkedHashMap<>();
        for (List<String> cells : rows) {
            if (cells.size() >= 2 && !cells.get(0).isBlank()) {
                info.put(cells.get(0), cells.get(1));
            }
        }
        return info;
    }
}
