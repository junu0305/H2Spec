package kr.go.h2spec.parser;

import kr.go.h2spec.parser.SpecLabels.Section;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * {@link Block} 목록을 문서 순서대로 훑어 오퍼레이션별 IR로 조립한다. DOCX/HWP 등 원본 포맷과 무관하게
 * 공유되는 로직이며, 각 포맷의 Reader가 만든 Block 목록만 있으면 된다.
 * <p>
 * 표준 기술문서는 "상세기능정보 → 요청 메시지 명세 → 응답 메시지 명세" 순서로 표를 두지만, 실제 문서는
 * 구간 제목의 표기가 제각각이고, 정보 표 없이 "요청 URL" 문단만 두거나, 요청·응답을 한 표에 담거나,
 * 긴 표를 쪽마다 나누기도 한다. 그래서 제목 문자열 하나에 기대지 않고 다음 단서를 함께 쓴다.
 * <ul>
 *   <li>구간: 헤딩과 표 안의 구간 행("입력값 명세", "출력값 명세")이 요청/응답/예제/에러 구간을 정한다.</li>
 *   <li>필드 표: 머리행의 라벨({@link FieldColumns})로 알아본다. 머리행 없는 표가 바로 뒤에 같은 폭으로
 *       이어지면 앞 표가 쪽 나눔으로 갈라진 것으로 보고 열 위치를 이어받는다.</li>
 *   <li>오퍼레이션 경계: 정보 표, 상세기능 구간 제목, 서비스 개요 표, 주소 표지 문단, 응답 뒤에 다시 시작하는
 *       요청 표. "데이터 공통 사항" 구간의 필드는 오퍼레이션이 아니라 뒤따르는 오퍼레이션마다 붙인다.</li>
 *   <li>주소: Call Back URL → 표 안의 END POINT URL → 오퍼레이션명을 담은 요청 예제 주소 → 서비스 URL +
 *       오퍼레이션 영문명 → "요청 URL" 문단 → 요청 예제 주소 → 스킴 없는 Call Back URL → 서비스 URL 순으로 찾는다.
 *       요청 예제는 문서가 적은 주소가 틀렸을 때(앞 오퍼레이션 주소를 베낀 Call Back URL 등) 바로잡는 데도 쓴다.</li>
 *   <li>필드: 응답 필드 표가 없으면 JSON 응답 예제의 키로, 요청 명세에 serviceKey가 없는 게이트웨이 주소는
 *       인증키 파라미터를 채워 만든다. 문서가 직접 적지 않은 값에는 reviewNotes를 남긴다.</li>
 * </ul>
 * 주소나 응답 필드를 찾지 못한 오퍼레이션은 건너뛰고 {@link #skipped()}에 이유를 남긴다.
 * 한 오퍼레이션의 결함 때문에 문서 전체를 버리지 않기 위해서다.
 */
class SpecBlockAssembler {

    /** 서비스 개요 표에서 찾은 기본 주소를 IrAssembler로 넘기는 키 */
    static final String SERVICE_URL_KEY = "__serviceUrl";
    /** 정보 표 밖에서 확정한 오퍼레이션 주소를 IrAssembler로 넘기는 키 */
    static final String ENDPOINT_URL_KEY = "__endpointUrl";
    /** 조립 과정에서 사람이 확인해야 할 점을 IrAssembler의 reviewNotes로 넘기는 키 */
    static final String REVIEW_NOTE_KEY = "__reviewNote";
    /** 값이 비어 있음을 뜻하는 표기 */
    private static final String NOT_AVAILABLE = "N/A";
    /** 요청 예제 표의 첫 칸 ("요청메시지", "REST(URI)") */
    private static final Pattern EXAMPLE_REQUEST_LABEL =
            Pattern.compile("^(요청메시지|요청메세지|요청url|요청예제|rest\\(ur[il]\\)|호출url|호출예제|requesturl)$");
    /** 응답 결과 코드 필드 이름꼴. 구간 단서가 없을 때 응답 표를 알아보는 데 쓴다 */
    private static final Pattern RESULT_CODE_NAME = Pattern.compile("(?i)^result_?code$");
    /** 응답 예제(JSON)의 "키": 값 쌍. 예제는 말줄임(…)과 둥근 따옴표가 섞여 JSON 파서로 읽히지 않는다 */
    private static final Pattern JSON_MEMBER = Pattern.compile(
            "\"([A-Za-z_$][\\w$]*)\"\\s*:\\s*(\"(?:[^\"\\\\]|\\\\.)*\"|“[^”]*”|-?[\\d.]+(?:[eE][-+]?\\d+)?|true|false|null|\\{|\\[)");
    /** 공공데이터포털 게이트웨이 주소 */
    private static final Pattern GATEWAY_URL = Pattern.compile("(?i)^https?://apis\\.data\\.go\\.kr/");
    private static final String SERVICE_KEY = "serviceKey";
    /** 공통 사항 구간 제목 ("데이터 공통 사항"). "공통코드"처럼 참고 표를 여는 제목과 구분한다 */
    private static final Pattern COMMON_TITLE = Pattern.compile("공통(사항|파라미터|요청|응답|항목|변수)");
    private static final int MAX_COMMON_TITLE_LENGTH = 30;
    /** 필드 이름으로 받아들일 꼴. 예제 주소·XML 조각이 필드로 들어오는 것을 막는다 */
    private static final Pattern FIELD_NAME = Pattern.compile("^[\\p{L}_$@][\\p{L}\\p{N}_.\\-$@:\\[\\]/ ]*$");

    private final IrAssembler assembler = new IrAssembler();
    private final List<String> skipped = new ArrayList<>();

    List<ParsedApi> parse(List<Block> blocks, String sourceFile, String sourceFormat) {
        skipped.clear();
        Run run = new Run(sourceFile, sourceFormat);
        for (Block block : blocks) {
            if (block instanceof Block.Heading heading) {
                run.heading(heading.text());
            } else if (block instanceof Block.Table table) {
                run.table(table.rows());
            }
        }
        run.flush();
        return run.finish();
    }

    /** 마지막 parse에서 건너뛴 오퍼레이션과 그 이유 */
    List<String> skipped() {
        return List.copyOf(skipped);
    }

    /** 오퍼레이션을 하나도 못 찾았을 때의 안내. 건너뛴 이유가 있으면 함께 알린다 */
    static String notFoundMessage(String fileName, List<String> skipped) {
        String message = "문서에서 상세기능 명세를 찾지 못했습니다: " + fileName;
        return skipped.isEmpty() ? message : message + " (" + String.join("; ", skipped) + ")";
    }

    /** 조립 중인 오퍼레이션 하나 */
    private static final class Operation {
        private final Map<String, String> info = new LinkedHashMap<>();
        private final List<List<String>> request = new ArrayList<>();
        private final List<List<String>> response = new ArrayList<>();
        /** 필드 표 안의 END POINT URL 행 */
        private String tableUrl;
        /** "요청 URL : …" 문단 */
        private String lineUrl;
        /** 요청 예제의 주소 */
        private String exampleUrl;
        /** 응답 필드 표가 없을 때 필드를 뽑을 응답 예제(JSON) */
        private String responseSample;
        /** "데이터 공통 사항"처럼 모든 오퍼레이션에 붙는 공통 파라미터 구간 */
        private boolean common;

        boolean hasFields() {
            return !request.isEmpty() || !response.isEmpty();
        }

        boolean isEmpty() {
            return info.isEmpty() && !hasFields() && tableUrl == null && lineUrl == null;
        }
    }

    /** 주소까지 정한 오퍼레이션. 문서를 다 읽은 뒤 주소가 겹치는 것을 바로잡고 IR로 조립한다 */
    private static final class Prepared {
        private final String label;
        private final Map<String, String> info;
        private final List<List<String>> request;
        private final List<List<String>> response;
        private final String exampleUrl;
        private final List<String> notes;
        private String url;

        private Prepared(String label, Map<String, String> info, List<List<String>> request,
                         List<List<String>> response, String url, String exampleUrl, List<String> notes) {
            this.label = label;
            this.info = info;
            this.request = request;
            this.response = response;
            this.url = url;
            this.exampleUrl = exampleUrl;
            this.notes = notes;
        }
    }

    /** 앞 필드 표를 이어받을 때 필요한 상태 */
    private record FieldTable(FieldColumns columns, Section section, int width) {
    }

    /** 문서 하나를 훑는 동안의 상태 */
    private final class Run {
        private final String sourceFile;
        private final String sourceFormat;
        private final List<ParsedApi> result = new ArrayList<>();
        private Operation operation = new Operation();
        private Section section;
        private String serviceUrl;
        /** 바로 앞 블록이 필드 표였으면 그 표. 머리행 없는 다음 표가 이어받는다 */
        private FieldTable lastField;
        /** "요청 URL" 표지만 있는 문단 다음 줄에 주소가 온다 */
        private boolean urlLabelPending;
        /** 공통 사항 구간의 요청/응답 필드. 뒤따르는 오퍼레이션마다 붙인다 */
        private final List<List<String>> commonRequest = new ArrayList<>();
        private final List<List<String>> commonResponse = new ArrayList<>();
        /** 주소까지 정한 오퍼레이션 */
        private final List<Prepared> prepared = new ArrayList<>();

        private Run(String sourceFile, String sourceFormat) {
            this.sourceFile = sourceFile;
            this.sourceFormat = sourceFormat;
        }

        void heading(String text) {
            if (SpecLabels.isTocEntry(text)) {
                return;
            }
            String labeledUrl = SpecLabels.labeledUrl(text);
            if (labeledUrl == null && urlLabelPending) {
                labeledUrl = SpecLabels.firstUrl(text);
            }
            if (labeledUrl != null) {
                urlLabelPending = false;
                lineUrl(labeledUrl);
                return;
            }
            if (SpecLabels.isUrlLabel(text)) {
                urlLabelPending = true;
                return;
            }
            if (isCommonTitle(text)) {
                // 응답까지 모은 오퍼레이션 뒤이거나 아직 아무것도 모으지 않았을 때만 공통 구간을 연다
                if (!operation.response.isEmpty()) {
                    startOperation();
                }
                if (!operation.hasFields()) {
                    operation.common = true;
                }
            }
            Section next = SpecLabels.headingSection(text);
            if (next == null) {
                if (section == Section.EXAMPLE && operation.exampleUrl == null) {
                    operation.exampleUrl = SpecLabels.firstUrl(text);
                }
                return;
            }
            lastField = null;
            urlLabelPending = false;
            if (next == Section.INFO && operation.hasFields()) {
                startOperation();
            }
            section = next;
        }

        private boolean isCommonTitle(String text) {
            String key = SpecLabels.normalize(text);
            return COMMON_TITLE.matcher(key).find() && key.length() <= MAX_COMMON_TITLE_LENGTH && !key.startsWith("※");
        }

        /**
         * 주소 표지 문단은 새 오퍼레이션을 연다. 앞 오퍼레이션이 응답까지 모았거나 이미 주소 문단을 가졌으면 끊는다.
         * 요청 표 다음에 주소 문단을 둔 문서도 있어, 요청만 모은 오퍼레이션은 끊지 않고 그 주소로 삼는다.
         */
        private void lineUrl(String url) {
            if (!operation.response.isEmpty() || operation.lineUrl != null) {
                startOperation();
            }
            operation.lineUrl = url;
        }

        void table(List<List<String>> rows) {
            if (rows.isEmpty()) {
                return;
            }
            boolean fieldHeader = hasFieldHeader(rows);
            // 서비스 URL은 개요 표에서만 찾는다. 필드 표의 "서비스URL" 행은 응답 필드일 뿐이다
            String foundServiceUrl = fieldHeader ? null : serviceUrl(rows);
            if (foundServiceUrl != null) {
                // 서비스 개요 표는 새 서비스 구간을 연다. 앞 오퍼레이션은 앞 서비스 주소로 마무리해야 한다
                if (operation.hasFields()) {
                    startOperation();
                }
                serviceUrl = foundServiceUrl;
                if (section == Section.ERROR || section == Section.EXAMPLE) {
                    section = Section.OTHER;
                }
            }
            if (section == Section.ERROR && isInfoTable(rows)) {
                // 에러 코드 정리 뒤에 구간 제목 없이 다음 오퍼레이션의 정보 표가 오는 문서
                section = Section.INFO;
            }
            if (section == Section.ERROR) {
                // 에러 코드 표와 에러 응답 형식 표는 필드 명세가 아니다
                lastField = null;
            } else if (fieldHeader) {
                fieldTable(rows, null);
            } else if (isInfoTable(rows) || (section == Section.INFO && foundServiceUrl == null)) {
                infoTable(rows);
            } else if (lastField != null && rows.get(0).size() == lastField.width()
                    && section != Section.EXAMPLE && !isExampleTable(rows) && looksLikeFieldRows(rows, lastField.columns())) {
                // 머리행 없이 앞 표와 같은 폭으로 이어지는 표 — 쪽 나눔으로 갈라진 필드 표
                fieldTable(rows, lastField);
            } else if ((section == Section.REQUEST || section == Section.RESPONSE) && foundServiceUrl == null
                    && rows.get(0).size() >= FieldColumns.STANDARD_WIDTH && !isExampleTable(rows)
                    && looksLikeFieldRows(rows, FieldColumns.positional())) {
                // 머리행 라벨을 알아보지 못한 표도 요청/응답 구간에서는 표준 열 순서로 읽는다 (이전 동작)
                fieldTable(rows, new FieldTable(FieldColumns.positional(), null, rows.get(0).size()));
            } else {
                exampleUrl(rows);
                responseSample(rows);
                lastField = null;
            }
        }

        /**
         * 머리행 없는 표가 필드 행들인지 본다. 이름 자리에 영문 식별자를 적은 행이 절반 이상이어야 한다.
         * 필드 표 뒤 안내 문단 다음에 같은 폭의 코드표("지점코드 | 지점명 | …")가 와도 흡수하지 않는다.
         */
        private boolean looksLikeFieldRows(List<List<String>> rows, FieldColumns columns) {
            int candidates = 0;
            int identifiers = 0;
            for (List<String> cells : rows) {
                if (cells.stream().allMatch(String::isBlank)) {
                    continue;
                }
                candidates++;
                List<String> row = columns.canonical(cells);
                if (row != null && FieldColumns.isIdentifier(row.get(FieldColumns.NAME))) {
                    identifiers++;
                }
            }
            return candidates > 0 && identifiers * 2 >= candidates;
        }

        /** 한 칸짜리 표에 담긴 JSON 응답 예제를 기억한다. 응답 필드 표가 없을 때만 쓴다 */
        private void responseSample(List<List<String>> rows) {
            String only = null;
            for (List<String> cells : rows) {
                for (String cell : cells) {
                    if (cell.isBlank()) {
                        continue;
                    }
                    if (only != null) {
                        return;
                    }
                    only = cell.strip();
                }
            }
            if (only != null && (only.startsWith("{") || only.startsWith("[")) && operation.responseSample == null) {
                operation.responseSample = only;
            }
        }

        private boolean hasFieldHeader(List<List<String>> rows) {
            for (int r = 0; r < rows.size(); r++) {
                if (header(rows, r) != null) {
                    return true;
                }
            }
            return false;
        }

        /**
         * r번째 행이 필드 표 머리행이면 열 위치를 돌려준다. 구분 열로 요청/응답을 가르는 머리행은
         * 코드표("구분 | 코드 | 코드명")와 모양이 같아, 아래 행에 In/Out 값이 있을 때만 받아들인다.
         */
        private FieldColumns header(List<List<String>> rows, int r) {
            FieldColumns columns = FieldColumns.detect(rows.get(r));
            if (columns == null || !columns.needsDirection()) {
                return columns;
            }
            for (int i = r + 1; i < rows.size(); i++) {
                if (columns.direction(rows.get(i)) != null) {
                    return columns;
                }
            }
            return null;
        }

        /**
         * 상세기능 정보 표. 오퍼레이션 번호·주소 키가 있거나 첫 칸이 구간 이름이면 정보 표다.
         * 오퍼레이션 목록 표("번호 | 오퍼레이션명(영문) | …")는 이 키들을 갖지 않는다.
         */
        private boolean isInfoTable(List<List<String>> rows) {
            if (!rows.get(0).isEmpty() && SpecLabels.isInfoFirstCell(rows.get(0).get(0))) {
                return true;
            }
            for (List<String> cells : rows) {
                for (int i = 0; i < cells.size(); i++) {
                    String cell = cells.get(i);
                    if (SpecLabels.isOperationNumberKey(cell)) {
                        return true;
                    }
                    if (SpecLabels.isEndpointKey(cell) && !valueAfter(cells, i).isEmpty()) {
                        return true;
                    }
                }
            }
            return false;
        }

        private void infoTable(List<List<String>> rows) {
            Map<String, String> info = toKeyValue(rows);
            // 앞 오퍼레이션이 필드를 모았거나, 정보 표가 연달아 와서 둘 다 주소를 가지면 새 오퍼레이션이다
            if (operation.hasFields() || (hasEndpoint(operation.info) && hasEndpoint(info))) {
                startOperation();
            }
            info.forEach(operation.info::putIfAbsent);
            lastField = null;
        }

        private boolean hasEndpoint(Map<String, String> info) {
            return info.keySet().stream().anyMatch(SpecLabels::isEndpointKey);
        }

        /**
         * 필드 표를 행 단위로 읽는다. 한 표 안에 구간 행과 머리행이 여러 번 나올 수 있다
         * (입력값 명세 / 머리행 / … / 출력값 명세 / 머리행 / …).
         */
        private void fieldTable(List<List<String>> rows, FieldTable inherited) {
            FieldColumns columns = inherited == null ? null : inherited.columns();
            Section local = inherited == null ? null : inherited.section();
            List<List<String>> lastTarget = null;
            int lastDepth = -1;
            for (int r = 0; r < rows.size(); r++) {
                List<String> cells = rows.get(r);
                Section labeled = SpecLabels.rowSection(cells);
                if (labeled != null) {
                    local = enter(labeled);
                    continue;
                }
                FieldColumns header = header(rows, r);
                if (header != null) {
                    columns = header;
                    if (header.section() != null) {
                        local = enter(header.section());
                    }
                    continue;
                }
                if (keyValueRow(cells) || columns == null) {
                    continue;
                }
                Section direction = columns.direction(cells);
                if (direction != null && direction != local) {
                    local = enter(direction);
                }
                List<String> row = columns.canonical(cells);
                if (row == null || !FIELD_NAME.matcher(row.get(FieldColumns.NAME)).matches() || isGroupLabel(row)) {
                    continue;
                }
                if (local == null) {
                    Section inferred = inferSection(rows);
                    if (inferred == Section.RESPONSE && operation.request.isEmpty() && operation.response.isEmpty()
                            && isRequestContent(rows)) {
                        // 요청 표에 "응답 메시지 명세" 제목을 잘못 단 문서: serviceKey가 있고 결과 코드가 없으면 요청이다
                        inferred = Section.REQUEST;
                    }
                    local = enter(inferred);
                }
                List<List<String>> target = local == Section.REQUEST ? operation.request : operation.response;
                int depth = columns.depth(cells);
                if (target == lastTarget && depth > lastDepth && !target.isEmpty()
                        && isValueless(target.get(target.size() - 1))) {
                    // 바로 앞 행이 이 행을 감싸는 컨테이너(itemList, korCompList)였다. 크기·샘플이 있는 행은
                    // 들여쓰기가 어긋났을 뿐 실제 필드라 남긴다
                    target.remove(target.size() - 1);
                }
                target.add(row);
                lastTarget = target;
                lastDepth = depth;
            }
            lastField = columns == null ? null : new FieldTable(columns, local, rows.get(0).size());
        }

        /** 구간에 들어선다. 응답을 모은 뒤 다시 요청이 시작되면 다음 오퍼레이션이다 */
        private Section enter(Section next) {
            if (next == Section.REQUEST && !operation.response.isEmpty()) {
                startOperation();
            }
            return next;
        }

        /**
         * 표 안의 키/값 행("메시지명(영문)/END POINT URL | https://…", "상세기능명 | 주식권리일정 조회").
         * 행의 첫 칸만 키로 본다. 데이터 행의 칸 값이 우연히 키와 같아도 필드를 잃지 않기 위해서다.
         */
        private boolean keyValueRow(List<String> cells) {
            int key = firstNonBlank(cells);
            // 키와 값 두 칸만 적힌 행이어야 한다. endPoint·apiUrl 같은 이름의 데이터 행은 다른 칸도 채운다
            if (key < 0 || cells.stream().filter(cell -> !cell.isBlank()).distinct().count() > 2) {
                return false;
            }
            String cell = cells.get(key);
            if (SpecLabels.isEndpointKey(cell)) {
                String url = SpecLabels.firstUrl(valueAfter(cells, key));
                if (url != null && operation.tableUrl == null) {
                    operation.tableUrl = url;
                }
                return true;
            }
            if (SpecLabels.isOperationNameKey(cell) && !valueAfter(cells, key).isEmpty()) {
                operation.info.putIfAbsent(cell, valueAfter(cells, key));
                return true;
            }
            return false;
        }

        private int firstNonBlank(List<String> cells) {
            for (int i = 0; i < cells.size(); i++) {
                if (!cells.get(i).isBlank()) {
                    return i;
                }
            }
            return -1;
        }

        /**
         * 표 안에서 필드를 묶는 라벨 행("공통", "전통식품"). 이름 칸에만 영문 식별자가 아닌 글이 있고
         * 나머지 칸이 모두 비어 있다.
         */
        private boolean isGroupLabel(List<String> row) {
            if (row.get(FieldColumns.NAME).matches("[\\x00-\\x7f]+")) {
                return false;
            }
            return row.subList(1, row.size()).stream().allMatch(String::isBlank);
        }

        /** 크기도 샘플도 없는 행. 하위 필드를 감싸기만 하는 컨테이너 행의 모양이다 */
        private boolean isValueless(List<String> row) {
            return isBlankOrDash(row.get(FieldColumns.SIZE)) && isBlankOrDash(row.get(FieldColumns.SAMPLE));
        }

        private boolean isBlankOrDash(String value) {
            return value.isBlank() || value.strip().equals("-");
        }

        private boolean isRequestContent(List<List<String>> rows) {
            boolean serviceKey = rows.stream().flatMap(List::stream)
                    .anyMatch(cell -> SERVICE_KEY.equalsIgnoreCase(cell.strip()));
            boolean resultCode = rows.stream().flatMap(List::stream)
                    .anyMatch(cell -> RESULT_CODE_NAME.matcher(cell.strip()).matches());
            return serviceKey && !resultCode;
        }

        /**
         * 헤딩도 구간 행도 없는 필드 표의 구간. 헤딩 구간을 우선 쓰고, 없으면
         * 아직 요청을 모으지 않았고 결과 코드가 없는 표를 요청으로 본다.
         */
        private Section inferSection(List<List<String>> rows) {
            if (section == Section.REQUEST || section == Section.RESPONSE) {
                return section;
            }
            boolean hasResultCode = rows.stream().flatMap(List::stream)
                    .anyMatch(cell -> RESULT_CODE_NAME.matcher(cell.strip()).matches());
            if (hasResultCode || !operation.request.isEmpty()) {
                return Section.RESPONSE;
            }
            return Section.REQUEST;
        }

        /** 요청 예제 표에서 주소를 꺼낸다. 예제 구간이거나 첫 칸이 요청 예제 표지인 표만 본다 */
        private void exampleUrl(List<List<String>> rows) {
            if (operation.exampleUrl != null || !(section == Section.EXAMPLE || isExampleTable(rows))) {
                return;
            }
            // 요청 표지 행 다음에 주소가 오며, "ServiceKey=…https://…"처럼 칸 중간에 주소가 있기도 하다
            for (List<String> cells : rows) {
                for (String cell : cells) {
                    String url = SpecLabels.firstUrl(cell);
                    if (url != null) {
                        operation.exampleUrl = url;
                        return;
                    }
                }
            }
        }

        private boolean isExampleTable(List<List<String>> rows) {
            List<String> first = rows.get(0);
            return !first.isEmpty() && EXAMPLE_REQUEST_LABEL.matcher(SpecLabels.normalize(first.get(0))).matches();
        }

        private void startOperation() {
            flush();
            operation = new Operation();
        }

        void flush() {
            Operation done = operation;
            operation = new Operation();
            if (done.isEmpty()) {
                return;
            }
            String label = describe(done);
            List<String> notes = new ArrayList<>();
            String url = endpointUrl(done, notes);
            if (done.common && url == null) {
                // 공통 사항 구간: 그 자체는 오퍼레이션이 아니고 뒤 오퍼레이션에 붙는다
                commonRequest.addAll(done.request);
                commonResponse.addAll(done.response);
                return;
            }
            List<List<String>> response = withCommon(commonResponse, done.response);
            if (done.response.isEmpty() && done.responseSample != null) {
                // 예제에서 뽑은 필드는 이름과 샘플뿐이라, 같은 이름이 공통 응답 표에 있으면 그 정의를 쓴다
                List<List<String>> sampled = sampleFields(done.responseSample);
                response = definedBy(commonResponse, sampled);
                if (!sampled.isEmpty()) {
                    notes.add("응답 필드 표가 없어 응답 예제(JSON)의 키로 응답 필드를 만듦 — 실제 응답 구조와 경로를 확인 필요");
                }
            }
            if (response.isEmpty()) {
                if (done.hasFields() || !done.info.isEmpty()) {
                    skipped.add(label + ": 응답 필드 표를 찾지 못했습니다");
                }
                return;
            }
            if (url == null) {
                skipped.add(label + ": 오퍼레이션 주소(Call Back URL 등)를 찾지 못했습니다");
                return;
            }
            List<List<String>> request = withCommon(commonRequest, done.request);
            if (GATEWAY_URL.matcher(url).find() && request.stream()
                    .noneMatch(row -> SERVICE_KEY.equalsIgnoreCase(row.get(FieldColumns.NAME)))) {
                request = new ArrayList<>(request);
                request.add(0, serviceKeyRow());
                notes.add("요청 명세 표에 serviceKey가 없어 공공데이터포털 게이트웨이 인증키 파라미터를 추가함");
            }
            Map<String, String> info = new LinkedHashMap<>(done.info);
            if (serviceUrl != null) {
                info.put(SERVICE_URL_KEY, serviceUrl);
            }
            prepared.add(new Prepared(label, info, distinctByName(request), distinctByName(response), url,
                    done.exampleUrl, notes));
        }

        /**
         * 주소가 겹치는 오퍼레이션을 바로잡은 뒤 IR로 조립한다. 두 기능이 같은 주소를 가질 수는 없으므로,
         * 앞뒤 기능의 Call Back URL을 베껴 두고 고치지 않은 문서로 보고 자기 요청 예제가 다른 주소를 부르는
         * 오퍼레이션은 예제 주소를 쓴다(전통식품정보 getFoodHistoryList, TourAPI areaIntlDivList).
         */
        List<ParsedApi> finish() {
            // 바로잡기 전 주소로 겹침을 판정한다. 앞 것을 고친 뒤 다시 보면 뒤 것은 더 이상 겹치지 않는다
            List<String> documented = prepared.stream().map(op -> op.url).toList();
            for (Prepared op : prepared) {
                long sharing = documented.stream().filter(url -> sameUrl(url, op.url)).count();
                if (sharing > 1 && op.exampleUrl != null && !sameUrl(op.exampleUrl, op.url) && sameHost(op.exampleUrl, op.url)) {
                    op.notes.add("Call Back URL이 다른 오퍼레이션과 같아 요청 예제의 주소를 씀: " + op.url);
                    op.url = op.exampleUrl;
                }
            }
            for (Prepared op : prepared) {
                op.info.put(ENDPOINT_URL_KEY, op.url);
                if (!op.notes.isEmpty()) {
                    op.info.put(REVIEW_NOTE_KEY, String.join("\n", op.notes));
                }
                try {
                    result.add(assembler.assemble(sourceFile, sourceFormat, op.info, op.request, op.response));
                } catch (IllegalArgumentException e) {
                    skipped.add(op.label + ": " + e.getMessage());
                }
            }
            return result;
        }

        /**
         * 공공데이터포털 게이트웨이(apis.data.go.kr)는 모든 호출에 serviceKey를 요구하지만, 요청 명세 표에서
         * 이를 빼고 본문 설명으로만 적는 기관이 있다. 게이트웨이가 아닌 기관 서버는 인증 방식이 제각각이라 채우지 않는다.
         */
        private List<String> serviceKeyRow() {
            List<String> row = new ArrayList<>(Collections.nCopies(FieldColumns.COUNT, ""));
            row.set(FieldColumns.NAME, SERVICE_KEY);
            row.set(FieldColumns.KOR_NAME, "인증키");
            row.set(FieldColumns.REQUIRED, "1");
            row.set(FieldColumns.DESCRIPTION, "공공데이터포털에서 발급받은 인증키");
            return row;
        }

        /**
         * 같은 이름의 필드는 처음 것만 남긴다. 한 오퍼레이션에 응답 표를 변형별로 여럿 두는 문서
         * ("바다낚시지수(갯바위)", "(선상)")에서 공통 필드가 겹친다.
         */
        private List<List<String>> distinctByName(List<List<String>> rows) {
            Set<String> seen = new HashSet<>();
            List<List<String>> distinct = new ArrayList<>();
            for (List<String> row : rows) {
                if (seen.add(row.get(FieldColumns.NAME))) {
                    distinct.add(row);
                }
            }
            return distinct;
        }

        /** 예제 순서를 지키되, 공통 표에 같은 이름이 있으면 그 행(설명·타입)으로 바꾼다 */
        private List<List<String>> definedBy(List<List<String>> definitions, List<List<String>> sampled) {
            List<List<String>> merged = new ArrayList<>();
            for (List<String> row : sampled) {
                String name = row.get(FieldColumns.NAME);
                merged.add(definitions.stream().filter(d -> d.get(FieldColumns.NAME).equals(name))
                        .findFirst().orElse(row));
            }
            return merged;
        }

        /** 공통 사항 필드를 앞에 붙인다. 오퍼레이션이 같은 이름을 직접 적었으면 그쪽을 쓴다 */
        private List<List<String>> withCommon(List<List<String>> common, List<List<String>> own) {
            if (common.isEmpty()) {
                return own;
            }
            List<List<String>> merged = new ArrayList<>();
            for (List<String> row : common) {
                String name = row.get(FieldColumns.NAME);
                if (own.stream().noneMatch(r -> r.get(FieldColumns.NAME).equals(name))) {
                    merged.add(row);
                }
            }
            merged.addAll(own);
            return merged;
        }

        /**
         * 응답 예제(JSON)의 값 키를 문서 순서대로 필드로 만든다. 객체·배열을 값으로 갖는 키는
         * 하위 필드를 감싸는 컨테이너라 빼고, 배열의 원소가 되풀이하는 키는 한 번만 담는다.
         */
        private List<List<String>> sampleFields(String sample) {
            List<List<String>> rows = new ArrayList<>();
            Set<String> seen = new HashSet<>();
            Matcher member = JSON_MEMBER.matcher(sample);
            while (member.find()) {
                String name = member.group(1);
                String value = member.group(2);
                if (value.equals("{") || value.equals("[") || !seen.add(name)) {
                    continue;
                }
                List<String> row = new ArrayList<>(Collections.nCopies(FieldColumns.COUNT, ""));
                row.set(FieldColumns.NAME, name);
                row.set(FieldColumns.SAMPLE, value.replaceAll("^[\"“]|[\"”]$", ""));
                rows.add(row);
            }
            return rows;
        }

        private String describe(Operation done) {
            String name = operationName(done.info);
            return name != null ? name : "오퍼레이션 " + (prepared.size() + skipped.size() + 1);
        }

        /**
         * 오퍼레이션 주소를 정한다. Call Back URL이 서비스 주소와 같아도 그대로 쓴다 —
         * 국립해양조사원 문서처럼 서비스 주소 자체가 호출 주소이고 요청 예제도 그 주소를 쓰는 기관이 있다.
         */
        private String endpointUrl(Operation done, List<String> notes) {
            String name = operationName(done.info);
            String callBack = infoUrl(done.info);
            if (callBack != null) {
                if (done.exampleUrl != null && serviceUrl != null && !isUnder(callBack, serviceUrl)
                        && isUnder(done.exampleUrl, serviceUrl) && lastSegment(done.exampleUrl).equals(lastSegment(callBack))) {
                    // Call Back URL의 서비스 경로를 잘못 적은 문서(…/wellnessTursmSyncList/wellnessTursmSyncList):
                    // 요청 예제가 문서의 서비스 주소 아래에서 같은 오퍼레이션을 부르면 예제가 맞다
                    notes.add("Call Back URL이 서비스 URL 밖을 가리켜 요청 예제의 주소를 씀: " + callBack);
                    return done.exampleUrl;
                }
                if (done.exampleUrl != null && normalizeUrl(done.exampleUrl).startsWith(normalizeUrl(callBack) + "/")) {
                    // Call Back URL에 서비스 주소만 적고 오퍼레이션 경로를 빠뜨린 문서: 요청 예제가 그 아래 경로를 부른다
                    return done.exampleUrl;
                }
                return callBack;
            }
            if (done.tableUrl != null) {
                return done.tableUrl;
            }
            if (done.exampleUrl != null && name != null && lastSegment(done.exampleUrl).contains(name.toLowerCase(Locale.ROOT))) {
                // Call Back URL이 비었거나 "서비스URL/getX" 같은 자리표시면 요청 예제의 실제 주소가 가장 정확하다
                // (trafficLightList → …/trafficLightList4, 기관 서버 주소 대신 게이트웨이 주소)
                return done.exampleUrl;
            }
            if (serviceUrl != null && name != null) {
                // 서비스 URL에 오퍼레이션 경로까지 적은 문서는 이름을 다시 붙이지 않는다 (…/getSumperfuel5m)
                String joined = lastSegment(serviceUrl).equals(name.toLowerCase(Locale.ROOT))
                        ? serviceUrl.replaceAll("/+$", "")
                        : serviceUrl.replaceAll("/+$", "") + "/" + name;
                // 서비스 주소 자체가 호출 주소인 기관이 있다. 요청 예제가 그렇게 부르면 예제를 따른다
                if (done.exampleUrl != null && sameUrl(done.exampleUrl, serviceUrl) && !sameUrl(joined, serviceUrl)) {
                    return done.exampleUrl;
                }
                return joined;
            }
            if (done.lineUrl != null) {
                return done.lineUrl;
            }
            if (done.exampleUrl != null) {
                return done.exampleUrl;
            }
            String schemeless = schemelessUrl(done.info);
            if (schemeless != null) {
                notes.add("Call Back URL에 스킴이 없어 https://를 붙임: " + schemeless);
                return "https://" + schemeless;
            }
            if (serviceUrl != null && !done.common) {
                notes.add("오퍼레이션 주소가 없어 서비스 URL을 그대로 오퍼레이션 주소로 씀");
                return serviceUrl;
            }
            return null;
        }

        /** "portal.kosha.or.kr/openapi/v1/koshagw"처럼 스킴 없이 적은 Call Back URL */
        private String schemelessUrl(Map<String, String> info) {
            for (Map.Entry<String, String> entry : info.entrySet()) {
                String value = entry.getValue().strip();
                if (SpecLabels.isEndpointKey(entry.getKey())
                        && value.matches("(?i)[a-z0-9-]+(\\.[a-z0-9-]+)+(:\\d+)?/[\\w./{}-]*")) {
                    return value.replaceAll("/+$", "");
                }
            }
            return null;
        }

        private String lastSegment(String url) {
            String trimmed = url.replaceAll("/+$", "");
            return trimmed.substring(trimmed.lastIndexOf('/') + 1).toLowerCase(Locale.ROOT);
        }

        /** url이 base 주소 아래 경로인지 (스킴·대소문자 무시) */
        private boolean isUnder(String url, String base) {
            return normalizeUrl(url).startsWith(normalizeUrl(base) + "/");
        }

        private boolean sameHost(String a, String b) {
            return normalizeUrl(a).split("/")[0].equals(normalizeUrl(b).split("/")[0]);
        }

        /** 스킴(http/https)과 끝 슬래시, 대소문자를 무시하고 비교한다 */
        private boolean sameUrl(String a, String b) {
            return normalizeUrl(a).equals(normalizeUrl(b));
        }

        private String normalizeUrl(String url) {
            return url.replaceFirst("(?i)^https?://", "").replaceAll("/+$", "").toLowerCase(Locale.ROOT);
        }

        private String infoUrl(Map<String, String> info) {
            for (Map.Entry<String, String> entry : info.entrySet()) {
                if (!SpecLabels.isEndpointKey(entry.getKey())) {
                    continue;
                }
                String value = entry.getValue().strip();
                if (value.isEmpty() || NOT_AVAILABLE.equalsIgnoreCase(value)) {
                    continue;
                }
                String url = SpecLabels.firstUrl(joinWrapped(value));
                if (url != null) {
                    return url;
                }
            }
            return null;
        }

        /** 칸 폭 때문에 공백·줄바꿈으로 꺾인 주소를 잇는다. 주소 뒤에 한글 설명이 붙은 칸은 그대로 둔다 */
        private String joinWrapped(String value) {
            return value.matches("[\\x21-\\x7e\\s]+") ? value.replaceAll("\\s+", "") : value;
        }

        /** 정보 표의 영문 오퍼레이션명. 이름 키에 영문 식별자가 들어 있을 때만 쓴다 */
        private String operationName(Map<String, String> info) {
            for (String key : SpecLabels.operationNameKeys()) {
                for (Map.Entry<String, String> entry : info.entrySet()) {
                    if (SpecLabels.normalize(entry.getKey()).equals(key)) {
                        String value = entry.getValue().strip();
                        if (value.matches("[A-Za-z_][A-Za-z0-9_]*")) {
                            return value;
                        }
                    }
                }
            }
            return null;
        }

        /**
         * 서비스 개요 표의 "서비스 URL" 행에서 기본 주소를 찾는다. 운영환경 값을 우선한다.
         * 라벨이 첫 칸("서비스URL | 개발환경 | 주소")에도, 구간 이름 다음 칸("API 서비스배포정보 | 서비스 URL | 주소")에도
         * 오고, 개발/운영 두 행으로 나뉘면 아래 행은 라벨 칸이 비어 있다.
         */
        private String serviceUrl(List<List<String>> rows) {
            String found = null;
            String production = null;
            boolean inUrlSection = false;
            for (List<String> cells : rows) {
                int label = -1;
                for (int i = 0; i < cells.size(); i++) {
                    if (SpecLabels.isServiceUrlKey(cells.get(i))) {
                        label = i;
                        break;
                    }
                }
                int from;
                if (label >= 0) {
                    inUrlSection = true;
                    from = label + 1;
                } else if (inUrlSection && startsBlankOrEnvironment(cells)) {
                    from = 0;
                } else {
                    inUrlSection = false;
                    continue;
                }
                for (int i = from; i < cells.size(); i++) {
                    String url = cells.get(i).strip().toLowerCase(Locale.ROOT).startsWith("http")
                            ? SpecLabels.firstUrl(joinWrapped(cells.get(i).strip())) : null;
                    if (url != null) {
                        found = url;
                        if (cells.subList(0, i).stream().anyMatch(SpecLabels::isProductionLabel)) {
                            production = url;
                        }
                    }
                }
            }
            return production != null ? production : found;
        }

        /** 서비스 URL 라벨이 세로로 병합돼 비었거나, 개발/운영환경 칸으로 시작하는 행 */
        private boolean startsBlankOrEnvironment(List<String> cells) {
            for (String cell : cells) {
                if (cell.isBlank()) {
                    continue;
                }
                String key = SpecLabels.normalize(cell);
                return key.contains("환경") || key.startsWith("http") || key.equals("운영") || key.equals("개발");
            }
            return false;
        }
    }

    /**
     * 키 다음의 첫 비어 있지 않은 칸을 값으로 본다. 가로 병합을 펼치며 채운 빈 칸이
     * 키와 값 사이에 끼기 때문에 바로 다음 칸만 보면 값을 놓친다.
     */
    private static String valueAfter(List<String> cells, int keyIndex) {
        for (int i = keyIndex + 1; i < cells.size(); i++) {
            if (!cells.get(i).isBlank()) {
                return cells.get(i);
            }
        }
        return "";
    }

    /**
     * 정보 표를 키/값으로 펼친다. 한 행에 키/값 쌍이 여럿 오거나 첫 열이 구간 이름으로
     * 병합된 문서가 있어, 행 안의 인접한 두 칸을 모두 후보로 본다.
     * 같은 키가 여러 번 나오면 먼저 나온 값을 쓴다.
     */
    private static Map<String, String> toKeyValue(List<List<String>> rows) {
        Map<String, String> info = new LinkedHashMap<>();
        for (List<String> cells : rows) {
            for (int i = 0; i + 1 < cells.size(); i++) {
                if (!cells.get(i).isBlank()) {
                    info.putIfAbsent(cells.get(i), valueAfter(cells, i));
                }
            }
        }
        return info;
    }
}
