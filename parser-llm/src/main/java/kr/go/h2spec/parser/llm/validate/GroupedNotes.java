package kr.go.h2spec.parser.llm.validate;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Supplier;

/**
 * 출처 하나의 검수 노트를 모은다. 항목마다 같은 문구로 나오는 노트는 종류별로 한 줄로 묶는다.
 * rule 엔진이 같은 종류 노트를 묶는 것과 같은 이유다. 지어낸 필드가 여럿이면 줄마다 따로 두는 노트가
 * 그 사이의 다른 노트를 가린다.
 * <p>
 * 묶은 줄의 형식은 rule 엔진의 묶은 줄 앞에 출처를 붙인 {@code [출처] 종류 (N개): 이름1, 이름2}이다. 이름은 처음 나온 순서대로 한 번씩 적고,
 * 묶은 줄은 그 종류의 첫 노트가 나온 자리에 둔다. 검증과 정규화가 같이 쓴다.
 */
public final class GroupedNotes {

    private final NotePrefix prefix;
    /** 노트가 나온 순서. 묶는 노트는 다 모은 뒤에 줄을 만들어야 해서 줄 대신 만드는 법을 담는다 */
    private final List<Supplier<String>> entries = new ArrayList<>();
    private final Map<String, Set<String>> groups = new LinkedHashMap<>();

    /** @param prefix 이 목록의 모든 노트에 붙일 출처 */
    public GroupedNotes(NotePrefix prefix) {
        this.prefix = prefix;
    }

    /**
     * 한 줄짜리 노트를 더한다.
     *
     * @param text 출처를 붙이기 전의 노트 글
     */
    public void add(String text) {
        String line = prefix.note(text);
        entries.add(() -> line);
    }

    /**
     * 항목 하나에 대한 노트를 더한다. 같은 종류의 노트는 첫 노트 자리의 한 줄로 묶인다.
     *
     * @param kind 노트 종류 (묶은 줄의 앞부분)
     * @param item 이름이나 경로
     */
    public void add(String kind, String item) {
        Set<String> items = groups.get(kind);
        if (items == null) {
            Set<String> group = new LinkedHashSet<>();
            groups.put(kind, group);
            entries.add(() -> prefix.note(kind + " (" + group.size() + "개): " + String.join(", ", group)));
            items = group;
        }
        items.add(item);
    }

    /** @return IR {@code metadata.reviewNotes}에 넣을 줄 (출처가 붙은 글) */
    public List<String> lines() {
        List<String> lines = new ArrayList<>();
        entries.forEach(entry -> lines.add(entry.get()));
        return lines;
    }
}
