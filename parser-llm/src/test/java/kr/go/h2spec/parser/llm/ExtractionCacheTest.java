package kr.go.h2spec.parser.llm;

import kr.go.h2spec.parser.llm.client.LlmRequest;
import kr.go.h2spec.parser.llm.client.LlmResponse;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.time.Duration;
import java.time.Instant;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ExtractionCacheTest {

    private static final LlmResponse.Usage USAGE = new LlmResponse.Usage(10, 0, 0, 5, 0);

    @TempDir
    Path tempDir;

    @Test
    void 오래_쓰지_않은_응답과_멈춘_임시_파일만_지우고_꺼낸_응답은_시각을_새로_해_남긴다() throws Exception {
        ExtractionCache cache = ExtractionCache.at(tempDir);
        ExtractionCache.Key idle = key("오래 쓰지 않은 문서");
        ExtractionCache.Key used = key("다시 꺼낸 문서");
        cache.store(idle, new LlmResponse("{}", "claude-sonnet-5-5", USAGE, LlmResponse.Finish.COMPLETE));
        cache.store(used, new LlmResponse("{}", "claude-sonnet-5-5", USAGE, LlmResponse.Finish.COMPLETE));
        Path tempFile = Files.writeString(tempDir.resolve(idle.sha256() + ".json.123.tmp"), "{");
        Path other = Files.writeString(tempDir.resolve("README.txt"), "사용자가 둔 파일");
        // --cache-dir로 다른 파일이 있는 디렉터리를 줄 수 있다. 캐시가 만든 꼴이 아닌 임시 파일은 남의 것이다
        Path othersTemp = Files.writeString(tempDir.resolve("download.tmp"), "다른 프로그램의 임시 파일");
        FileTime old = FileTime.from(Instant.now().minus(Duration.ofDays(40)));
        for (Path file : new Path[] {file(idle), file(used), tempFile, other, othersTemp}) {
            Files.setLastModifiedTime(file, old);
        }

        assertTrue(cache.find(used).isPresent());
        int removed = cache.prune(ExtractionCache.DEFAULT_MAX_IDLE, Instant.now());

        assertEquals(1, removed);
        assertFalse(Files.exists(file(idle)));
        assertFalse(Files.exists(tempFile), "저장하다 멈춘 임시 파일도 지운다");
        assertTrue(Files.exists(file(used)), "꺼낸 응답은 시각을 새로 해 남긴다");
        assertTrue(Files.exists(other), "응답 파일 꼴이 아닌 파일은 건드리지 않는다");
        assertTrue(Files.exists(othersTemp), "캐시가 저장할 때 만드는 꼴이 아닌 .tmp 파일은 건드리지 않는다");
        assertEquals(0, ExtractionCache.disabled().prune(ExtractionCache.DEFAULT_MAX_IDLE, Instant.now()));
    }

    private ExtractionCache.Key key(String document) {
        return ExtractionCache.key(new LlmRequest("시스템", document, "지시", "{}", 1000), "claude", "claude-sonnet-5-5",
                "medium");
    }

    private Path file(ExtractionCache.Key key) {
        return tempDir.resolve(key.sha256() + ".json");
    }
}
