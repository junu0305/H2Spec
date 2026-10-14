package kr.go.h2spec.parser;

import kr.go.h2spec.parser.SpecLabels.Section;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
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
 *       요청 예제는 문서가 적은 주소가 틀렸을 때(앞 오퍼레이션 주소를 베낀 Call Back URL 등) 바로잡는 데도 쓴다.
 *       llm 엔진도 같은 규칙으로 고르도록 규칙 자체는 {@link IrConventions}에 두고, 여기서는 후보를 모은다.</li>
 *   <li>필드: 응답 필드 표가 없으면 JSON 응답 예제의 키로, 요청 명세에 serviceKey가 없는 게이트웨이 주소는
 *       인증키 파라미터를 채워 만든다. 문서가 직접 적지 않은 값에는 reviewNotes를 남긴다.</li>
 * </ul>
 * 주소나 응답 필드를 찾지 못한 오퍼레이션은 건너뛰고 {@link #skipped()}에 이유를 남긴다.
 * 한 오퍼레이션의 결함 때문에 문서 전체를 버리지 않기 위해서다.
 */
class SpecBlockAssembler {

    /** 서비스 개요 표에서 찾은 기본 주소를 IrAssembler로 넘기는 키 */
    static final String SERVICE_URL_KEY = "__serviceUrl";
    /** 주소 후보에서 고르고 겹치는 주소를 바로잡은 오퍼레이션 주소를 IrAssembler로 넘기는 키 */
    static final String ENDPOINT_URL_KEY = "__endpointUrl";
    /** 조립 과정에서 사람이 확인해야 할 점을 IrAssembler의 reviewNotes로 넘기는 키 */
    static final String REVIEW_NOTE_KEY = "__reviewNote";
    /** 게이트웨이 serviceKey를 보충할 오퍼레이션임을 IrAssembler로 넘기는 키 */
    static final String GATEWAY_SERVICE_KEY_FLAG = "__gatewayServiceKey";
    /** 요청 예제 표의 첫 칸 ("요청메시지", "REST(URI)") */
    private static final Pattern EXAMPLE_REQUEST_LABEL =
            Pattern.compile("^(요청메시지|요청메세지|요청url|요청예제|rest\\(ur[il]\\)|호출url|호출예제|requesturl)$");
    /** 응답 결과 코드 필드 이름꼴. 구간 단서가 없을 때 응답 표를 알아보는 데 쓴다 */
    private static final Pattern RESULT_CODE_NAME = Pattern.compile("(?i)^result_?code$");
    /** 응답 예제(JSON)의 "키": 값 쌍. 예제는 말줄임(…)과 둥근 따옴표가 섞여 JSON 파서로 읽히지 않는다 */
    private static final Pattern JSON_MEMBER = Pattern.compile(
            "\"([A-Za-z_$][\\w$]*)\"\\s*:\\s*(\"(?:[^\"\\\\]|\\\\.)*\"|“[^”]*”|-?[\\d.]+(?:[eE][-+]?\\d+)?|true|false|null|\\{|\\[)");
    /** 공통 사항 구간 제목 ("데이터 공통 사항"). "공통코드"처럼 참고 표를 여는 제목과 구분한다 */
    private static final Pattern COMMON_TITLE = Pattern.compile("공통(사항|파라미터|요청|응답|항목|변수)");
    private static final int MAX_COMMON_TITLE_LENGTH = 30;
    /** 필드 이름으로 받아들일 꼴. 예제 주소·XML 조각이 필드로 들어오는 것을 막는다 */
    private static final Pattern FIELD_NAME = Pattern.compile("^[\\p{L}_$@][\\p{L}\\p{N}_.\\-$@:\\[\\]/ ]*$");

    private final IrAssembler assembler = new IrAssembler();
    private final List<String> skipped = new ArrayList<>();
    /** 찾은 오퍼레이션 (문서 순서). 주소까지 정한 것은 자리만 잡아 두고 finish에서 결과로 채운다 */
    private final List<RuleEngine.OperationResult> operations = new ArrayList<>();

    List<ParsedApi> parse(List<Block> blocks, String sourceFile, String sourceFormat) {
        skipped.clear();
        operations.clear();
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

    /** 마지막 parse에서 찾은 오퍼레이션마다의 결과 (문서 순서). 공통 사항 구간은 담지 않는다 */
    List<RuleEngine.OperationResult> operations() {
        return List.copyOf(operations);
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
        /** "데이터 공통 사항"처럼 뒤따르는 오퍼레이션마다 붙는 공통 사항 구간 */
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
        private final String url;
        private final String exampleUrl;
        private final List<String> notes;
        /** operations에서 이 오퍼레이션의 자리 */
        private final int position;

        private Prepared(String label, Map<String, String> info, List<List<String>> request,
                         List<List<String>> response, String url, String exampleUrl, List<String> notes, int position) {
            this.label = label;
            this.info = info;
            this.request = request;
            this.response = response;
            this.url = url;
            this.exampleUrl = exampleUrl;
            this.notes = notes;
            this.position = position;
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
                labeledUrl = IrConventions.firstUrl(text);
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
                    operation.exampleUrl = IrConventions.firstUrl(text);
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
                String url = IrConventions.firstUrl(valueAfter(cells, key));
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
                    .anyMatch(cell -> IrConventions.SERVICE_KEY.equalsIgnoreCase(cell.strip()));
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
                    String url = IrConventions.firstUrl(cell);
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
            String url = IrConventions.endpointUrl(candidates(done), notes);
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
                    skip(done, label, "응답 필드 표를 찾지 못했습니다");
                }
                return;
            }
            if (url == null) {
                skip(done, label, "오퍼레이션 주소(Call Back URL 등)를 찾지 못했습니다");
                return;
            }
            List<List<String>> request = withCommon(commonRequest, done.request);
            Map<String, String> info = new LinkedHashMap<>(done.info);
            if (serviceUrl != null) {
                info.put(SERVICE_URL_KEY, serviceUrl);
            }
            List<String> requestNames = request.stream().map(row -> row.get(FieldColumns.NAME)).toList();
            if (IrConventions.needsGatewayServiceKey(url, requestNames)) {
                // 파라미터는 IrAssembler가 경로 변수 다음 자리에 넣는다. 노트는 여기서 남겨야
                // 주소를 고른 노트 다음, 겹치는 주소를 바로잡은 노트 앞에 온다
                info.put(GATEWAY_SERVICE_KEY_FLAG, "true");
                notes.add(IrConventions.GATEWAY_SERVICE_KEY_NOTE);
            }
            operations.add(new RuleEngine.OperationResult(operationName(done.info), title(done.info), null, null));
            prepared.add(new Prepared(label, info, distinctByName(request), distinctByName(response), url,
                    done.exampleUrl, notes, operations.size() - 1));
        }

        /** 오퍼레이션을 건너뛴다. 건너뛴 것도 교차검증에서 짝을 지을 수 있게 오퍼레이션 결과로 남긴다 */
        private void skip(Operation done, String label, String reason) {
            skipped.add(label + ": " + reason);
            operations.add(new RuleEngine.OperationResult(operationName(done.info), title(done.info), null, reason));
        }

        /** 주소가 겹치는 오퍼레이션을 바로잡은 뒤({@link IrConventions#correctSharedUrls}) IR로 조립한다 */
        List<ParsedApi> finish() {
            List<String> urls = IrConventions.correctSharedUrls(
                    prepared.stream().map(op -> op.url).toList(),
                    prepared.stream().map(op -> op.exampleUrl).toList(),
                    prepared.stream().map(op -> op.notes).toList());
            for (int i = 0; i < prepared.size(); i++) {
                Prepared op = prepared.get(i);
                op.info.put(ENDPOINT_URL_KEY, urls.get(i));
                if (!op.notes.isEmpty()) {
                    op.info.put(REVIEW_NOTE_KEY, String.join("\n", op.notes));
                }
                RuleEngine.OperationResult found = operations.get(op.position);
                try {
                    ParsedApi api = assembler.assemble(sourceFile, sourceFormat, op.info, op.request, op.response);
                    result.add(api);
                    operations.set(op.position, new RuleEngine.OperationResult(found.operationName(), found.title(),
                            api, null));
                } catch (IllegalArgumentException e) {
                    skipped.add(op.label + ": " + e.getMessage());
                    operations.set(op.position, new RuleEngine.OperationResult(found.operationName(), found.title(),
                            null, e.getMessage()));
                }
            }
            return result;
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

        /** 오퍼레이션이 모은 칸에서 꺼낸 주소 후보. 서비스 URL은 이 오퍼레이션이 속한 서비스 구간의 것이다 */
        private UrlCandidates candidates(Operation done) {
            return new UrlCandidates(infoUrl(done.info), schemelessUrl(done.info), done.tableUrl, done.lineUrl,
                    done.exampleUrl, serviceUrl, operationName(done.info), done.common);
        }

        /** 정보 표의 국문 기능명. IR의 apiName과 같은 값이며 교차검증이 오퍼레이션을 짝짓는 데 쓴다 */
        private String title(Map<String, String> info) {
            String title = IrAssembler.infoValue(info, SpecLabels.operationTitleKeys());
            return title == null ? null : IrText.oneLine(title);
        }

        /** 정보 표의 주소 키 칸 가운데 처음으로 주소를 담은 칸의 주소 */
        private String infoUrl(Map<String, String> info) {
            for (Map.Entry<String, String> entry : info.entrySet()) {
                if (SpecLabels.isEndpointKey(entry.getKey())) {
                    String url = IrConventions.callBackUrl(entry.getValue());
                    if (url != null) {
                        return url;
                    }
                }
            }
            return null;
        }

        /** 정보 표의 주소 키 칸에 "portal.kosha.or.kr/openapi/v1/koshagw"처럼 스킴 없이 적은 주소 */
        private String schemelessUrl(Map<String, String> info) {
            for (Map.Entry<String, String> entry : info.entrySet()) {
                if (SpecLabels.isEndpointKey(entry.getKey())) {
                    String url = IrConventions.schemelessUrl(entry.getValue());
                    if (url != null) {
                        return url;
                    }
                }
            }
            return null;
        }

        /** 정보 표의 영문 오퍼레이션명. 이름 키의 우선순위대로 보고, 칸 글이 영문 식별자일 때만 쓴다 */
        private String operationName(Map<String, String> info) {
            for (String key : SpecLabels.operationNameKeys()) {
                for (Map.Entry<String, String> entry : info.entrySet()) {
                    if (SpecLabels.normalize(entry.getKey()).equals(key)) {
                        String name = IrConventions.operationName(entry.getValue());
                        if (name != null) {
                            return name;
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
                    String url = IrConventions.serviceUrl(cells.get(i));
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
