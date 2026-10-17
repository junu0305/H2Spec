package kr.go.h2spec.parser.llm.client;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.StringReader;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

class SseReaderTest {

    @Test
    void 빈_줄로_이벤트를_나누고_여러_data_줄은_줄바꿈으로_잇는다() throws IOException {
        List<SseReader.Event> events = read("""
                event: message_start
                data: {"a":1}

                data: 첫 줄
                data:둘째 줄

                """);

        assertEquals(List.of(new SseReader.Event("message_start", "{\"a\":1}"),
                new SseReader.Event("message", "첫 줄\n둘째 줄")), events,
                "이름이 없으면 message이고, 콜론 뒤 공백은 하나만 뺀다");
    }

    @Test
    void 줄_끝은_CRLF_LF_CR을_모두_받고_주석과_모르는_필드는_건너뛴다() throws IOException {
        List<SseReader.Event> events = read(": keep-alive\r\nevent: a\r\nid: 7\r\nretry: 10\r\ndata: 1\r\n\r\n"
                + "event: b\rdata: 2\r\r" + "event: c\ndata: 3\n\n");

        assertEquals(List.of(new SseReader.Event("a", "1"), new SseReader.Event("b", "2"), new SseReader.Event("c", "3")),
                events);
    }

    @Test
    void data_없는_이벤트는_내보내지_않고_그_이름도_다음_이벤트로_넘기지_않는다() throws IOException {
        List<SseReader.Event> events = read("event: ping\n\ndata: 1\n\n");

        assertEquals(List.of(new SseReader.Event("message", "1")), events);
    }

    @Test
    void 빈_줄로_끝나지_않은_마지막_이벤트는_버린다() throws IOException {
        List<SseReader.Event> events = read("data: 1\n\nevent: done\ndata: 2");

        assertEquals(List.of(new SseReader.Event("message", "1")), events, "줄 중간에서 연결이 끊겼을 수 있다");
    }

    @Test
    void 본문_맨_앞의_BOM은_버린다() throws IOException {
        List<SseReader.Event> events = read("\uFEFFevent: a\ndata: 1\n\nevent: b\ndata: \uFEFF2\n\n");

        assertEquals(List.of(new SseReader.Event("a", "1"), new SseReader.Event("b", "\uFEFF2")), events,
                "맨 앞이 아닌 BOM은 값의 일부다");
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
