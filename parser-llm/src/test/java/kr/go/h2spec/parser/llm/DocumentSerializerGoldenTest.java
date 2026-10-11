package kr.go.h2spec.parser.llm;

import kr.go.h2spec.parser.Block;
import kr.go.h2spec.parser.HwpSpecReader;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

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

    @Test
    void 설계_문서의_직렬화_예시가_그대로_나온다() throws Exception {
        String text = new DocumentSerializer().serialize(read());

        // 설계 4.1의 예시. 예시는 P25(ex> 예시 주소 문단)를 생략했다
        assertTrue(text.contains("""
                [P23] 요청 URL
                [P24] https://open.jejudatahub.net/api/proxy/7tttaDaD5ataaba75a57ttDtaDb6tb7t/{your_appkey}?{params(key=value)}
                """), text);
        assertTrue(text.contains("""
                [P26] Request Parameters
                [T27] 표 7행 4열
                | Name | Description | Type | Note |
                | searchDate | 분석일(필수) | string | YYYYMMDD |
                | city | 시군구 | string | 제주시,서귀포시 |
                """), text);
    }

    @Test
    void 같은_문서를_다시_읽어도_바이트_단위로_같은_글이_된다() throws Exception {
        // 결과 캐시 키와 회사별 프롬프트 캐싱이 이 성질에 기댄다
        byte[] first = new DocumentSerializer().serialize(read()).getBytes(StandardCharsets.UTF_8);
        byte[] second = new DocumentSerializer().serialize(read()).getBytes(StandardCharsets.UTF_8);

        assertArrayEquals(first, second);
    }

    private List<Block> read() throws Exception {
        return new HwpSpecReader().read(TestDocuments.path(DOCUMENT));
    }

    private Path resource(String name) throws Exception {
        return Path.of(getClass().getResource(name).toURI());
    }
}
