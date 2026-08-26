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

    /** 서비스 개요 표에서 찾은 기본 주소를 IrAssembler로 넘기는 키 */
    static final String SERVICE_URL_KEY = "__serviceUrl";

    private enum Pending {
        INFO, REQUEST, RESPONSE
    }

    private final IrAssembler assembler = new IrAssembler();

    List<ParsedApi> parse(List<Block> blocks, String sourceFile, String sourceFormat) {
        List<ParsedApi> result = new ArrayList<>();

        Map<String, String> info = null;
        List<List<String>> requestRows = null;
        Pending pending = null;
        String serviceUrl = null;

        for (Block block : blocks) {
            if (block instanceof Block.Heading heading) {
                Pending next = pendingFor(heading.text());
                if (next != null) {
                    pending = next;
                }
            } else if (block instanceof Block.Table table && pending == null) {
                // 헤딩으로 구간을 못 잡는 문서를 위해 표 자체의 생김새로도 알아본다
                if (serviceUrl == null) {
                    serviceUrl = serviceUrl(table.rows());
                }
                if (isOperationInfoTable(table.rows())) {
                    info = toKeyValue(table.rows());
                    if (serviceUrl != null) {
                        info.put(SERVICE_URL_KEY, serviceUrl);
                    }
                    requestRows = null;
                }
            } else if (block instanceof Block.Table table && pending != null) {
                switch (pending) {
                    case INFO -> {
                        info = toKeyValue(table.rows());
                        if (serviceUrl != null) {
                            info.put(SERVICE_URL_KEY, serviceUrl);
                        }
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

    /** 서비스 개요 표의 "서비스 URL" 행에서 기본 주소를 찾는다. 운영환경 값을 우선한다. */
    private String serviceUrl(List<List<String>> rows) {
        String found = null;
        boolean inUrlSection = false;
        for (List<String> cells : rows) {
            if (cells.isEmpty()) {
                continue;
            }
            if (cells.get(0).contains("서비스 URL")) {
                inUrlSection = true;
            } else if (!cells.get(0).isBlank()) {
                inUrlSection = false;
            }
            if (!inUrlSection) {
                continue;
            }
            for (String cell : cells) {
                if (cell.startsWith("http")) {
                    found = cell.trim();
                }
            }
        }
        return found;
    }

    /** 표준 문서의 "상세기능정보"에 대응하는 표. 첫 셀이 구간 이름을 담는 문서가 있다. */
    private boolean isOperationInfoTable(List<List<String>> rows) {
        return !rows.isEmpty() && !rows.get(0).isEmpty() && rows.get(0).get(0).contains("오퍼레이션 정보");
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

    /**
     * 정보 표를 키/값으로 펼친다. 한 행에 키/값 쌍이 여럿 오거나 첫 열이 구간 이름으로
     * 병합된 문서가 있어, 행 안의 인접한 두 칸을 모두 후보로 본다.
     * 같은 키가 여러 번 나오면 먼저 나온 값을 쓴다.
     */
    private Map<String, String> toKeyValue(List<List<String>> rows) {
        Map<String, String> info = new LinkedHashMap<>();
        for (List<String> cells : rows) {
            for (int i = 0; i + 1 < cells.size(); i++) {
                if (!cells.get(i).isBlank()) {
                    info.putIfAbsent(cells.get(i), cells.get(i + 1));
                }
            }
        }
        return info;
    }
}
