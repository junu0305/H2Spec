package kr.go.h2spec.eval;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;

/**
 * 평가할 추출 엔진. 지금은 rule 엔진뿐이고, llm 엔진은 1단계에서 {@code SpecParser}를 감싸 붙인다.
 */
public interface EvalEngine {

    /**
     * 리포트 디렉터리와 요약에 쓰는 엔진 이름.
     *
     * @return rule, llm 등
     */
    String name();

    /**
     * 문서에서 오퍼레이션을 찾는다.
     *
     * @param document 문서 경로
     * @return 찾은 오퍼레이션 (문서 순서). 건너뛴 것도 담는다
     * @throws IOException 문서를 읽지 못했을 때
     */
    List<EngineOperation> extract(Path document) throws IOException;
}
