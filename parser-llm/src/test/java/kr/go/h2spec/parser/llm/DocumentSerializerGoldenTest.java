package kr.go.h2spec.parser.llm;

import kr.go.h2spec.parser.Block;
import kr.go.h2spec.parser.HwpSpecReader;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * 실제 기관 문서를 Reader로 읽어 직렬화한 결과를 골든파일과 글자 하나까지 비교한다.
 * 문서는 parser corpus의 jejudatahub-wifi-usage.hwp로, 개정이력 표, 공통 구간, JSON 예제를 담은 1칸 표가 있다.
 * Reader나 직렬화 형식이 바뀌어 골든파일을 다시 만들면 같은 문서의 결과 캐시 키도 바뀐다.
 */
class DocumentSerializerGoldenTest {

    private static final String DOCUMENT = "jejudatahub-wifi-usage.hwp";

    @Test
    void 실제_문서를_골든파일과_같은_글로_바꾼다() throws Exception {
        String text = new DocumentSerializer().serialize(read());

        assertEquals(Files.readString(resource("/golden/jejudatahub-wifi-usage.txt")), text);
    }

    private List<Block> read() throws Exception {
        return new HwpSpecReader().read(TestDocuments.path(DOCUMENT));
    }

    private Path resource(String name) throws Exception {
        return Path.of(getClass().getResource(name).toURI());
    }
}
