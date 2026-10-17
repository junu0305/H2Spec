package kr.go.h2spec.eval;

import kr.go.h2spec.parser.DocumentReaders;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

/**
 * 평가 세트. 세트 A의 문서와 정답 파일은 저장소의 테스트 리소스 그 자체다.
 * 세트 B(docs/sample)는 정답 파일을 새로 써야 하고, 세트 C는 저장소 밖에 있고 정답이 없어 대리 지표로만 본다.
 */
final class DataSets {

    /** 정답 파일이 있는 corpus. 문서마다 같은 이름의 *.expected.json이 옆에 있다 */
    static final String CORPUS = "parser/src/test/resources/docs/corpus";
    /**
     * corpus 밖의 세트 A 문서. msrstn-info는 같은 문서의 DOCX·HWP·HWPX판이고 HWPX판은 cli 테스트 리소스에만 있다.
     * 모두 아직 정답 파일이 없다. 단위 테스트가 일부 값만 확인하고 있어, 정답을 지어내지 않고 정답 없는 문서로 둔다
     */
    static final List<String> EXTRA_SET_A = List.of(
            "parser/src/test/resources/docs/msrstn-info.docx",
            "parser/src/test/resources/docs/msrstn-info.hwp",
            "cli/src/test/resources/docs/msrstn-info.hwpx",
            "parser/src/test/resources/docs/nps-disability-review.hwp");
    private static final String GOLD_SUFFIX = ".expected.json";

    private DataSets() {
    }

    /**
     * 평가할 문서 하나.
     *
     * @param document 문서 경로
     * @param gold     정답 파일 경로. 없으면 null
     */
    record EvalDocument(Path document, Path gold) {
    }

    /**
     * 세트의 문서를 돌려준다. 지금은 세트 A뿐이다. 세트 B는 정답 파일의 자리가 정해지면 더한다.
     *
     * @param set  세트 이름
     * @param root 저장소 루트
     * @return 세트의 문서
     * @throws IOException 세트의 문서를 찾지 못했을 때
     */
    static List<EvalDocument> documents(String set, Path root) throws IOException {
        if (!"A".equals(set)) {
            throw new IllegalArgumentException("알 수 없는 세트입니다: " + set);
        }
        return setA(root);
    }

    /**
     * 세트 A의 문서를 corpus(파일 이름 순), 그 밖의 문서 순으로 돌려준다.
     *
     * @param root 저장소 루트
     * @return 세트 A 문서. corpus 밖의 문서는 있는 것만 담는다
     * @throws IOException corpus 디렉터리가 없거나 읽지 못했을 때. 대개 저장소 루트를 잘못 준 경우다
     */
    static List<EvalDocument> setA(Path root) throws IOException {
        Path corpus = root.resolve(CORPUS);
        if (!Files.isDirectory(corpus)) {
            throw new IOException("세트 A의 corpus를 찾지 못했습니다: " + corpus
                    + " (저장소 루트에서 실행하거나 --root로 저장소 루트를 주세요)");
        }
        List<EvalDocument> documents = new ArrayList<>();
        try (Stream<Path> files = Files.list(corpus)) {
            files.filter(Files::isRegularFile)
                    .filter(DocumentReaders::isDocument)
                    .sorted()
                    .forEach(document -> documents.add(new EvalDocument(document, goldFor(document))));
        }
        for (String extra : EXTRA_SET_A) {
            Path document = root.resolve(extra);
            if (Files.isRegularFile(document)) {
                documents.add(new EvalDocument(document, goldFor(document)));
            }
        }
        return documents;
    }

    /**
     * 문서의 정답 파일을 찾는다. 같은 디렉터리의 {@code <파일 이름>.expected.json}(msrstn-info.hwp.expected.json)을
     * 먼저 보고, 없으면 corpus처럼 확장자를 뗀 {@code <이름>.expected.json}을 본다.
     * msrstn-info처럼 이름이 같은 문서의 판끼리 내용이 다를 수 있어(HWP판은 응답 필드를 줄인 축소본이다) 판별 파일을 앞에 둔다.
     *
     * @param document 문서 경로
     * @return 정답 파일 경로. 없으면 null
     */
    static Path goldFor(Path document) {
        String fileName = document.getFileName().toString();
        Path perFormat = document.resolveSibling(fileName + GOLD_SUFFIX);
        if (Files.isRegularFile(perFormat)) {
            return perFormat;
        }
        int dot = fileName.lastIndexOf('.');
        Path shared = document.resolveSibling((dot > 0 ? fileName.substring(0, dot) : fileName) + GOLD_SUFFIX);
        return Files.isRegularFile(shared) ? shared : null;
    }
}
