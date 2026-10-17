package kr.go.h2spec.parser;

import kr.go.h2spec.parser.DocumentExamples.ExNode;
import kr.go.h2spec.parser.DocumentExamples.Example;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Pattern;

/**
 * 응답 예제로 응답 필드의 경로를 정한다. 응답 필드 표는 계층을 적지 않아 rule 엔진은 공공데이터 표준 구조
 * ({@code response.header}, {@code response.body.items.item[]})를 가정한다. 실제 응답이 다르면(루트가 rfcOpenApi·root,
 * 목록이 body.rows·data.list, 루트 없이 {"header", "body"}로 오는 JSON) 생성 DTO가 응답을 읽지 못한다.
 * <p>
 * 표의 필드 이름이 예제에서 절반 넘게 찾아질 때만 예제를 믿는다. 예제에 없는 필드는 예제에서 찾은 데이터 필드가 모인 자리
 * (목록 원소)에, 결과코드·메시지는 예제의 결과코드 자리에 둔다. 만든 경로가 하나의 트리가 되지 않으면 쓰지 않는다.
 */
final class ExampleStructure {

    /** 예제를 믿는 데 필요한, 예제에서 찾은 필드의 비율 */
    private static final double MIN_COVERAGE = 0.5;
    private static final int MIN_MATCHES = 2;
    /** 목록 원소를 담는 요소의 이름꼴. 예제에 원소가 하나뿐이어도 목록으로 본다 */
    private static final Pattern LIST_ELEMENT = Pattern.compile("(?i)^(item|row|record|list|data|info)$|(info|item|row)$");
    private static final Pattern LIST_CONTAINER = Pattern.compile("(?i)(items|list|rows|data|array|records)$");
    private static final String LIST_SUFFIX = "[]";

    /**
     * 정한 구조.
     *
     * @param paths    필드 이름별 경로({@code rfcOpenApi.body.data.list[].name})
     * @param rootless 루트 하나로 감싸지 않은 JSON 응답이면 true. 경로의 첫 칸이 맨 바깥 키다
     * @param format   예제의 형식("XML", "JSON")
     * @param renamed  예제의 표기로 고친 이름(표의 이름 → 예제의 이름). 대소문자만 다른 것이다
     */
    record Structure(Map<String, String> paths, boolean rootless, String format, Map<String, String> renamed) {
    }

    private ExampleStructure() {
    }

    /**
     * @param names       응답 필드 이름(표의 순서)
     * @param standard    rule 엔진이 가정한 경로(이름별). 예제로 정한 경로가 이와 모두 같으면 바꿀 것이 없어 빈 값을 돌려준다
     * @param formats     받아들일 예제 형식. 앞의 것을 먼저 본다
     */
    static Optional<Structure> resolve(List<String> names, Map<String, String> standard,
                                       List<Example.Format> formats, DocumentExamples examples) {
        if (names.isEmpty()) {
            return Optional.empty();
        }
        for (Example.Format format : formats) {
            Candidate best = null;
            for (Example example : examples.examples()) {
                if (example.format() != format || "OpenAPI_ServiceResponse".equals(example.root().name())) {
                    continue;
                }
                Candidate candidate = Candidate.of(example, names);
                if (candidate != null && (best == null || candidate.matched > best.matched)) {
                    best = candidate;
                }
            }
            if (best == null || best.matched < MIN_MATCHES || best.matched < names.size() * MIN_COVERAGE) {
                continue;
            }
            Optional<Structure> structure = best.structure(names, standard, format.name());
            if (structure.isEmpty()) {
                continue;
            }
            if (!structure.get().rootless() && structure.get().renamed().isEmpty()
                    && structure.get().paths().equals(standard)) {
                return Optional.empty();
            }
            return structure;
        }
        return Optional.empty();
    }

    /** 예제 하나에서 필드 이름을 찾은 결과 */
    private static final class Candidate {
        /** 경로의 첫 칸. 루트 없는 JSON이면 null */
        private final String rootName;
        private final Map<String, List<List<ExNode>>> leaves;
        /** 예제의 이름을 소문자로 바꾼 것 → 예제의 이름 */
        private final Map<String, String> lowerNames;
        private int matched;

        private Candidate(String rootName, Map<String, List<List<ExNode>>> leaves) {
            this.rootName = rootName;
            this.leaves = leaves;
            this.lowerNames = new HashMap<>();
            for (String name : leaves.keySet()) {
                lowerNames.putIfAbsent(name.toLowerCase(Locale.ROOT), name);
            }
        }

        static Candidate of(Example example, List<String> names) {
            ExNode base = example.root();
            String rootName = base.name();
            if (example.format() == Example.Format.JSON) {
                // 키 하나가 객체를 감싸면 그 키가 루트다({"response": {…}}). 아니면 루트 없는 응답이다
                if (base.children().size() == 1 && !base.children().get(0).isLeaf()
                        && !base.children().get(0).repeated()) {
                    base = base.children().get(0);
                    rootName = base.name();
                } else {
                    rootName = null;
                }
            }
            Candidate candidate = new Candidate(rootName, base.leafPaths());
            for (String name : names) {
                if (candidate.exampleName(name) != null) {
                    candidate.matched++;
                }
            }
            return candidate;
        }

        /** 표의 이름을 예제에서 찾은 이름. 같은 이름이 없으면 대소문자만 다른 이름. 없으면 null */
        String exampleName(String name) {
            if (leaves.containsKey(name)) {
                return name;
            }
            return lowerNames.get(name.toLowerCase(Locale.ROOT));
        }

        Optional<Structure> structure(List<String> names, Map<String, String> standard, String format) {
            // 데이터 필드가 가장 많이 모인 부모가 목록 원소다. 표준 구조에서 목록 원소에 둘 필드만 센다.
            // 결과코드·메시지와 페이징 필드(numOfRows 등)는 목록 원소 밖에 있다
            Map<String, Integer> parentCounts = new LinkedHashMap<>();
            Map<String, List<ExNode>> parentNodes = new HashMap<>();
            List<ExNode> resultParent = null;
            Map<String, List<ExNode>> chosen = new LinkedHashMap<>();
            for (String name : names) {
                String found = exampleName(name);
                if (found == null) {
                    continue;
                }
                List<ExNode> path = leaves.get(found).get(0);
                List<ExNode> parent = path.subList(0, path.size() - 1);
                if (DocumentExamples.isResultName(name)) {
                    if (resultParent == null) {
                        resultParent = parent;
                    }
                } else if (String.valueOf(standard.get(name)).contains(LIST_SUFFIX)) {
                    String key = key(parent);
                    parentCounts.merge(key, 1, Integer::sum);
                    parentNodes.putIfAbsent(key, parent);
                }
            }
            // 같은 수면 더 깊은 자리(목록 원소 쪽)를 고른다
            List<ExNode> record = parentCounts.entrySet().stream()
                    .max(Map.Entry.<String, Integer>comparingByValue()
                            .thenComparing(e -> parentNodes.get(e.getKey()).size()))
                    .map(e -> parentNodes.get(e.getKey())).orElse(null);
            for (String name : names) {
                String found = exampleName(name);
                if (found != null) {
                    List<List<ExNode>> options = leaves.get(found);
                    List<ExNode> pick = options.get(0);
                    List<ExNode> preferred = DocumentExamples.isResultName(name) ? resultParent : record;
                    for (List<ExNode> option : options) {
                        if (preferred != null && key(option.subList(0, option.size() - 1)).equals(key(preferred))) {
                            pick = option;
                            break;
                        }
                    }
                    chosen.put(name, pick);
                }
            }
            Map<String, String> paths = new LinkedHashMap<>();
            Map<String, String> renamed = new LinkedHashMap<>();
            for (String name : names) {
                String path;
                List<ExNode> nodes = chosen.get(name);
                if (nodes != null) {
                    String exampleName = nodes.get(nodes.size() - 1).name();
                    if (!exampleName.equals(name)) {
                        renamed.put(name, exampleName);
                    }
                    path = render(nodes.subList(0, nodes.size() - 1), record) + exampleName;
                } else {
                    List<ExNode> parent = DocumentExamples.isResultName(name) && resultParent != null ? resultParent
                            : record != null ? record : resultParent;
                    if (parent == null) {
                        return Optional.empty();
                    }
                    path = render(parent, record) + name;
                }
                if (nodes != null && !nodes.get(nodes.size() - 1).isLeaf()) {
                    return Optional.empty();
                }
                // 경로는 점으로 칸을 나누고 []로 목록을 적는다. 그런 글자가 든 이름은 경로로 적을 수 없다
                if (nodes != null && nodes.stream().anyMatch(n -> n.name().contains(".") || n.name().contains("["))) {
                    return Optional.empty();
                }
                paths.put(name, rootName == null ? path : rootName + "." + path);
            }
            return isTree(paths.values(), rootName == null)
                    ? Optional.of(new Structure(paths, rootName == null, format, renamed))
                    : Optional.empty();
        }

        /** 부모 경로를 "a.b[].c." 꼴로 적는다. 되풀이되는 노드와 목록 원소 꼴의 노드에 []를 붙인다 */
        private static String render(List<ExNode> parent, List<ExNode> record) {
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < parent.size(); i++) {
                ExNode node = parent.get(i);
                sb.append(node.name());
                boolean isRecord = record != null && i == record.size() - 1 && key(parent.subList(0, i + 1)).equals(key(record));
                String container = i > 0 ? parent.get(i - 1).name() : "";
                if (node.repeated() || (isRecord && (LIST_ELEMENT.matcher(node.name()).find()
                        || LIST_CONTAINER.matcher(container).find()))) {
                    sb.append(LIST_SUFFIX);
                }
                sb.append('.');
            }
            return sb.toString();
        }

        private static String key(List<ExNode> path) {
            StringBuilder sb = new StringBuilder();
            for (ExNode node : path) {
                sb.append(node.name()).append('/');
            }
            return sb.toString();
        }
    }

    /**
     * 경로들이 generator가 받는 하나의 트리가 되는지 본다. 이름에 점이 없고, 값인 경로가 다른 경로의 중간 칸이 아니고,
     * 같은 칸의 목록 표기가 같고, 루트가 있으면 첫 칸이 모두 같고 목록이 아니어야 한다.
     */
    static boolean isTree(java.util.Collection<String> paths, boolean rootless) {
        Map<String, Boolean> internal = new HashMap<>();
        java.util.Set<String> leaves = new java.util.HashSet<>();
        String root = null;
        for (String path : paths) {
            String[] segments = path.split("\\.");
            if (!rootless) {
                if (segments.length < 2 || segments[0].endsWith(LIST_SUFFIX)) {
                    return false;
                }
                if (root != null && !root.equals(segments[0])) {
                    return false;
                }
                root = segments[0];
            }
            StringBuilder prefix = new StringBuilder();
            for (int i = 0; i < segments.length; i++) {
                String segment = segments[i];
                String bare = segment.endsWith(LIST_SUFFIX) ? segment.substring(0, segment.length() - 2) : segment;
                if (bare.isEmpty()) {
                    return false;
                }
                String key = prefix + bare;
                if (i < segments.length - 1) {
                    Boolean list = internal.putIfAbsent(key, segment.endsWith(LIST_SUFFIX));
                    if ((list != null && list != segment.endsWith(LIST_SUFFIX)) || leaves.contains(key)) {
                        return false;
                    }
                } else if (internal.containsKey(key) || !leaves.add(key)) {
                    return false;
                }
                prefix.append(bare).append('.');
            }
        }
        return true;
    }
}
