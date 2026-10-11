package kr.go.h2spec.parser;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;

/**
 * 문서 하나를 문단·표 순서 그대로 {@link Block} 목록으로 읽는다. 형식(DOCX, HWP, HWPX)마다 하나씩 있고,
 * 확장자로 고르는 일은 {@link DocumentReaders}가 맡는다.
 * <p>
 * Block 목록은 문서마다 한 번만 만들어 두 엔진이 같이 쓴다. llm 엔진은 이것을 직렬화해 보내고,
 * 교차검증은 같은 목록으로 rule 엔진을 돌린다.
 */
public interface DocumentReader {

    /**
     * 문서를 읽는다.
     *
     * @param document 읽을 문서
     * @return 문서 순서대로의 문단·표 블록
     * @throws IOException 파일을 읽지 못했을 때
     */
    List<Block> read(Path document) throws IOException;
}
