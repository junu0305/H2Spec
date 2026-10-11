package kr.go.h2spec.parser;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Supplier;

/**
 * 오퍼레이션 하나의 검수 노트를 모은다. 필드마다 같은 문구로 나오는 노트는 종류별로 한 줄로 묶는다.
 * 기상청 중기예보 MidTa는 노트 42개가 모두 "샘플데이터 기반 integer 추론"이라, 줄마다 따로 두면
 * 검수자가 그 사이의 다른 노트를 놓친다.
 * <p>
 * 묶은 줄의 형식은 {@code 종류 (덧붙임, N개): 이름1, 이름2, …}다.
 * <ul>
 *   <li>이름은 처음 나온 순서대로 모두 적는다. 요청 파라미터와 응답 필드에 같은 이름이 있으면 한 번만 적는다.</li>
 *   <li>N은 적은 이름의 수다. 이름이 하나여도 같은 형식으로 써서, 한 종류의 노트는 늘 한 모양이다.</li>
 *   <li>묶은 줄은 그 종류의 첫 노트가 나온 자리에 둔다. 다른 노트의 순서는 그대로다.</li>
 * </ul>
 * 예: {@code 샘플데이터 기반 integer 추론 (코드형 문자열일 수 있음, 3개): taMin4, taMin4Low, taMin4High}
 */
final class ReviewNotes {

    /** 필드마다 나오는 노트의 종류 */
    enum FieldNote {
        /** 타입 열이 없어 숫자 샘플로 integer를 정한 필드. 코드·일자처럼 문자열이어야 하는 값일 수 있다 */
        SAMPLE_INTEGER("샘플데이터 기반 integer 추론", "코드형 문자열일 수 있음");

        private final String label;
        private final String caveat;

        FieldNote(String label, String caveat) {
            this.label = label;
            this.caveat = caveat;
        }

        private String line(Set<String> names) {
            return label + " (" + caveat + ", " + names.size() + "개): " + String.join(", ", names);
        }
    }

    /** 노트가 나온 순서. 묶는 노트는 다 모은 뒤에 줄을 만들어야 해서 줄 대신 만드는 법을 담는다 */
    private final List<Supplier<String>> entries = new ArrayList<>();
    private final Map<FieldNote, Set<String>> fields = new EnumMap<>(FieldNote.class);

    /** 한 줄짜리 노트를 더한다 */
    void add(String note) {
        entries.add(() -> note);
    }

    /** 필드 하나에 대한 노트를 더한다. 같은 종류의 노트는 첫 노트 자리의 한 줄로 묶인다 */
    void addField(FieldNote kind, String fieldName) {
        Set<String> names = fields.get(kind);
        if (names == null) {
            Set<String> group = new LinkedHashSet<>();
            fields.put(kind, group);
            entries.add(() -> kind.line(group));
            names = group;
        }
        names.add(fieldName);
    }

    /** IR metadata의 reviewNotes로 쓸 줄 */
    List<String> lines() {
        List<String> lines = new ArrayList<>();
        entries.forEach(entry -> lines.add(entry.get()));
        return lines;
    }
}
