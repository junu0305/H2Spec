package kr.go.h2spec.parser.llm.client;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.StringReader;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** SSE 해석 규칙 중 어댑터가 쓰는 것을 본다. */
class SseReaderTest {

    @Test
    void 빈_줄로_이벤트를_나누고_이름과_data를_읽는다() throws IOException {
        assertEquals(List.of(new SseReader.Event("message_start", "{\"type\":\"message_start\"}"),
                        new SseReader.Event("ping", "{\"type\": \"ping\"}")),
                read("event: message_start\ndata: {\"type\":\"message_start\"}\n\nevent: ping\ndata: {\"type\": \"ping\"}\n\n"));
    }

    @Test
    void CRLF와_CR_줄_끝도_받는다() throws IOException {
        assertEquals(List.of(new SseReader.Event("a", "1"), new SseReader.Event("b", "2")),
                read("event: a\r\ndata: 1\r\n\r\nevent: b\rdata: 2\r\r"));
    }

    @Test
    void 여러_data_줄은_줄바꿈으로_잇는다() throws IOException {
        assertEquals(List.of(new SseReader.Event("message", "첫 줄\n둘째 줄")), read("data: 첫 줄\ndata: 둘째 줄\n\n"));
    }

    @Test
    void 주석과_모르는_필드와_data_없는_이벤트는_건너뛴다() throws IOException {
        assertEquals(List.of(new SseReader.Event("ping", "{}")),
                read(": keep-alive\n\nevent: empty\n\nid: 7\nretry: 1000\nevent: ping\ndata: {}\n\n"));
    }

    @Test
    void 콜론_뒤의_공백은_하나만_뗀다() throws IOException {
        assertEquals(List.of(new SseReader.Event("message", "x"), new SseReader.Event("message", " x"),
                        new SseReader.Event("message", "")),
                read("data:x\n\ndata:  x\n\ndata\n\n"));
    }

    @Test
    void 빈_줄_없이_끝난_마지막_이벤트는_버린다() throws IOException {
        // 줄 중간에서 연결이 끊겼을 수 있다
        assertEquals(List.of(new SseReader.Event("a", "1")), read("event: a\ndata: 1\n\nevent: b\ndata: {\"type\":\"mes"));
    }

    private static List<SseReader.Event> read(String body) throws IOException {
        SseReader reader = new SseReader(new StringReader(body));
        List<SseReader.Event> events = new ArrayList<>();
        for (SseReader.Event event = reader.next(); event != null; event = reader.next()) {
            events.add(event);
        }
        return events;
    }
}
