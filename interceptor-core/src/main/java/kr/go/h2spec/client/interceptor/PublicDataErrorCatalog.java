package kr.go.h2spec.client.interceptor;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

/**
 * 공공데이터포털 표준 에러코드 사전.
 * <p>
 * 기관 문서는 코드의 뜻만 적어 두고 개발자가 무엇을 해야 하는지는 알려주지 않는다.
 * 여기에 뜻과 조치 안내를 함께 담아 {@link PublicDataApiException} 메시지에 붙인다.
 * 항목은 {@code error-codes.json}에 있으며, 추가 방법은 {@code docs/error-codes.md}에 있다.
 */
public final class PublicDataErrorCatalog {

    private static final String RESOURCE = "error-codes.json";

    private static final List<ErrorCode> ENTRIES = load();
    private static final Map<String, ErrorCode> BY_CODE = indexByCode(ENTRIES);
    private static final Map<String, ErrorCode> BY_NAME = indexByName(ENTRIES);

    private PublicDataErrorCatalog() {}

    /** 사전에 등록된 모든 항목. */
    public static List<ErrorCode> entries() {
        return ENTRIES;
    }

    /** 결과코드로 찾는다. 기관마다 {@code 1}과 {@code 01}을 섞어 쓰므로 자릿수는 무시한다. */
    public static Optional<ErrorCode> find(String resultCode) {
        return Optional.ofNullable(BY_CODE.get(normalizeCode(resultCode)));
    }

    /** 결과코드 없이 결과메시지에만 에러명을 담는 응답을 위해 에러명으로 찾는다. */
    public static Optional<ErrorCode> findByMessage(String resultMsg) {
        if (resultMsg == null) {
            return Optional.empty();
        }
        String upper = resultMsg.toUpperCase(Locale.ROOT);
        return BY_NAME.entrySet().stream()
                .filter(entry -> upper.contains(entry.getKey()))
                .map(Map.Entry::getValue)
                .findFirst();
    }

    /** 결과코드를 먼저 보고, 없으면 결과메시지로 다시 찾는다. */
    static Optional<ErrorCode> lookup(String resultCode, String resultMsg) {
        Optional<ErrorCode> byCode = find(resultCode);
        return byCode.isPresent() ? byCode : findByMessage(resultMsg);
    }

    /**
     * 앞자리 0을 떼어 {@code 1}과 {@code 01}을 같은 코드로 본다.
     * 숫자가 아닌 코드를 쓰는 기관도 있어 그때는 대문자로만 맞춘다.
     * <p>
     * 자릿수 제한 없이 문자열로 다룬다. 여기서 예외가 나면 오류를 알리려던 예외 생성이
     * 대신 터져 원래 오류가 가려진다.
     */
    private static String normalizeCode(String code) {
        if (code == null || code.isBlank()) {
            return "";
        }
        String trimmed = code.trim();
        if (!trimmed.chars().allMatch(Character::isDigit)) {
            return trimmed.toUpperCase(Locale.ROOT);
        }
        int start = 0;
        while (start < trimmed.length() - 1 && trimmed.charAt(start) == '0') {
            start++;
        }
        return trimmed.substring(start);
    }

    private static List<ErrorCode> load() {
        try (InputStream input = PublicDataErrorCatalog.class.getResourceAsStream(RESOURCE)) {
            if (input == null) {
                throw new IllegalStateException("에러코드 사전을 찾을 수 없습니다: " + RESOURCE);
            }
            JsonNode root = new ObjectMapper().readTree(input);
            List<ErrorCode> entries = new ArrayList<>();
            for (JsonNode node : root.path("codes")) {
                entries.add(toErrorCode(node));
            }
            return List.copyOf(entries);
        } catch (IOException e) {
            throw new IllegalStateException("에러코드 사전을 읽을 수 없습니다: " + RESOURCE, e);
        }
    }

    private static ErrorCode toErrorCode(JsonNode node) {
        List<String> nameAliases = new ArrayList<>();
        for (JsonNode alias : node.path("nameAliases")) {
            nameAliases.add(alias.asText());
        }
        return new ErrorCode(
                node.path("code").asText(),
                node.path("name").asText(),
                node.path("meaning").asText(),
                node.path("action").asText(),
                node.path("source").asText(),
                List.copyOf(nameAliases));
    }

    private static Map<String, ErrorCode> indexByCode(List<ErrorCode> entries) {
        Map<String, ErrorCode> index = new LinkedHashMap<>();
        for (ErrorCode entry : entries) {
            putUnique(index, normalizeCode(entry.code()), entry);
        }
        return Map.copyOf(index);
    }

    /**
     * 긴 이름부터 넣어, 짧은 이름이 부분 문자열로 먼저 걸리지 않게 한다
     * ({@code SERVICE_ACCESS_DENIED_ERROR}가 {@code UNKNOWN_ERROR}보다 앞).
     */
    private static Map<String, ErrorCode> indexByName(List<ErrorCode> entries) {
        Map<String, ErrorCode> unordered = new LinkedHashMap<>();
        for (ErrorCode entry : entries) {
            putUnique(unordered, entry.name().toUpperCase(Locale.ROOT), entry);
            for (String alias : entry.nameAliases()) {
                putUnique(unordered, alias.toUpperCase(Locale.ROOT), entry);
            }
        }
        Map<String, ErrorCode> index = new LinkedHashMap<>();
        unordered.entrySet().stream()
                .sorted((a, b) -> Integer.compare(b.getKey().length(), a.getKey().length()))
                .forEach(entry -> index.put(entry.getKey(), entry.getValue()));
        return index;
    }

    /** 사전 편집 실수로 같은 키가 두 번 들어오면 어느 쪽이 이길지 모르게 되므로 즉시 알린다. */
    private static void putUnique(Map<String, ErrorCode> index, String key, ErrorCode entry) {
        ErrorCode previous = index.put(key, entry);
        if (previous != null) {
            throw new IllegalStateException(
                    "에러코드 사전에 중복 항목이 있습니다: " + key + " (" + previous.name() + ", " + entry.name() + ")");
        }
    }

    /**
     * @param code 결과코드
     * @param name 문서에 적힌 에러명
     * @param meaning 문서에 적힌 뜻
     * @param action 개발자가 취할 조치
     * @param source 근거로 삼은 문서
     * @param nameAliases 같은 에러를 가리키는 다른 에러명 표기
     */
    public record ErrorCode(String code, String name, String meaning, String action,
                            String source, List<String> nameAliases) {
    }
}
