package kr.go.h2spec.parser;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * {@link Block} 목록을 "상세기능정보 → 요청 메시지 명세 → 응답 메시지 명세" 3구간 상태기계로 훑어
 * 오퍼레이션별 IR로 조립한다. DOCX/HWP 등 원본 포맷과 무관하게 공유되는 로직이며,
 * 각 포맷의 Reader가 만든 Block 목록만 있으면 된다.
 */
class SpecBlockAssembler {

    private enum Pending {
        INFO, REQUEST, RESPONSE
    }

    private final IrAssembler assembler = new IrAssembler();

    List<ParsedApi> parse(List<Block> blocks, String sourceFile, String sourceFormat) {
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
                                    sourceFile, sourceFormat, info, requestRows, table.rows()));
                        }
                        info = null;
                        requestRows = null;
                    }
                }
                pending = null;
            }
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
