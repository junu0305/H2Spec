package kr.go.h2spec.parser.llm.client;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.Reader;
import java.util.Objects;

/**
 * {@code text/event-stream} 본문(SSE)을 이벤트 하나씩 읽는다. 회사와 상관없는 형식이라 client 패키지에 두고,
 * 세 회사 어댑터가 스트리밍 응답을 읽을 때 쓴다.
 * <p>
 * WHATWG HTML 명세의 해석 규칙 중 어댑터가 쓰는 것만 따른다. 본문 맨 앞의 BOM은 버리고, 줄 끝은 CRLF·LF·CR을 모두 받고,
 * {@code :}로 시작하는 줄은 주석이며, 여러 {@code data} 줄은 줄바꿈으로 잇는다. {@code id}·{@code retry}는 끊긴 스트림을 이어 받을 때 쓰는 값인데,
 * 어댑터는 끊기면 처음부터 다시 부르므로 무시한다.
 */
public final class SseReader {

    private final BufferedReader reader;
    private boolean started;

    /** @param reader 응답 본문. 닫는 것은 부른 쪽이 한다 */
    public SseReader(Reader reader) {
        Objects.requireNonNull(reader, "reader");
        this.reader = reader instanceof BufferedReader buffered ? buffered : new BufferedReader(reader);
    }

    /**
     * @return 다음 이벤트. 본문이 끝나면 null
     * @throws IOException 본문을 읽지 못했을 때
     */
    public Event next() throws IOException {
        String name = "";
        StringBuilder data = null;
        for (String line = reader.readLine(); line != null; line = reader.readLine()) {
            if (!started) {
                started = true;
                // 명세대로 본문 맨 앞의 BOM 하나를 버린다. 두면 첫 줄의 필드 이름이 달라져 첫 이벤트의 이름을 잃는다
                if (line.startsWith("\uFEFF")) {
                    line = line.substring(1);
                }
            }
            if (line.isEmpty()) {
                // 빈 줄이 이벤트를 끝낸다. data가 없는 이벤트는 명세대로 내보내지 않는다
                if (data != null) {
                    return new Event(name.isEmpty() ? "message" : name, data.toString());
                }
                name = "";
                continue;
            }
            if (line.startsWith(":")) {
                continue;
            }
            int colon = line.indexOf(':');
            String field = colon < 0 ? line : line.substring(0, colon);
            String value = colon < 0 ? "" : line.substring(colon + 1);
            if (value.startsWith(" ")) {
                value = value.substring(1);
            }
            if (field.equals("event")) {
                name = value;
            } else if (field.equals("data")) {
                data = data == null ? new StringBuilder(value) : data.append('\n').append(value);
            }
        }
        // 빈 줄로 끝나지 않은 마지막 이벤트는 버린다(명세와 같다). 줄 중간에서 연결이 끊겼을 수 있다
        return null;
    }

    /**
     * SSE 이벤트 하나.
     *
     * @param name 이벤트 이름({@code event} 필드). 없으면 {@code message}
     * @param data {@code data} 필드. 여러 줄이었으면 줄바꿈으로 이었다
     */
    public record Event(String name, String data) {
    }
}
