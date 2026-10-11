package kr.go.h2spec.parser;

import java.io.IOException;
import java.nio.file.Path;

/**
 * 명세 문서를 오퍼레이션별 IR로 바꾸는 엔진의 공통 인터페이스. rule 엔진({@link RuleSpecParser})과
 * llm 엔진(parser-llm)이 구현하고, CLI는 어느 엔진이든 이것으로 문서를 변환한다.
 */
public interface SpecParser {

    /**
     * 문서를 읽어 오퍼레이션별 IR로 바꾼다.
     *
     * @param document 명세 문서. 확장자로 형식을 정한다({@link DocumentReaders})
     * @return 오퍼레이션마다의 결과와 엔진 정보
     * @throws IOException        문서를 읽지 못했을 때
     * @throws SpecParseException 엔진이 문서를 변환하지 못했을 때. 실패 종류로 CLI가 처리를 가른다
     * @throws IllegalArgumentException 명세 문서(.docx, .hwp, .hwpx)가 아닐 때
     */
    SpecParseResult parse(Path document) throws IOException, SpecParseException;
}
