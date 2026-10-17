package kr.go.h2spec.parser.llm;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import kr.go.h2spec.parser.llm.client.LlmRequest;
import kr.go.h2spec.parser.llm.client.LlmResponse;
import kr.go.h2spec.parser.llm.client.LlmResponse.Finish;
import kr.go.h2spec.parser.llm.client.LlmResponse.Usage;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.FileAttribute;
import java.nio.file.attribute.FileTime;
import java.nio.file.attribute.PosixFilePermission;
import java.nio.file.attribute.PosixFilePermissions;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.time.Instant;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * LLM 응답의 결과 캐시. 같은 입력과 같은 설정에서 같은 IR을 내고, 같은 요청에 다시 과금하지 않기 위한 장치다.
 * 팀 단위 재현성은 커밋된 IR이 맡는다.
 * <ul>
 *   <li>키는 실제로 보내는 요청 전체의 SHA-256이다({@link #key}). 버전 문자열 대신 내용으로 만들어, 프롬프트를 고치고 버전 올리기를 잊어도
 *       이전 결과가 조용히 재사용되지 않는다.</li>
 *   <li>값은 LLM이 돌려준 JSON 원본과 토큰 사용량이다. 검증·정규화 전의 값이라 그 코드를 고쳐도 LLM을 다시 부르지 않고 반영된다.</li>
 *   <li>{@link Finish#COMPLETE} 응답과, 잘린 응답의 잘린 글을 저장한다. 같은 키로는 다시 부르지(과금하지) 않고, 잘린 글에서는
 *       llm 엔진이 다 적힌 오퍼레이션만 건져 나눠 부르기를 이어 간다. 거절은 저장하지 않는다.</li>
 *   <li>꺼낼 때마다 파일 시각을 새로 한다. 오래 쓰지 않은 응답은 {@link #prune}이 지운다. 키에 프롬프트·스키마·모델이 들어가므로
 *       이것들이 바뀌면 이전 응답은 다시 쓰이지 않고 쌓이기만 하기 때문이다.</li>
 * </ul>
 * 키마다 파일 하나({@code <키>.json})를 둔다. 임시 파일에 다 쓴 뒤 옮겨서, 읽는 쪽이 반쯤 쓴 파일을 보지 않는다.
 * 문서에서 뽑은 내용이 담기므로 파일 시스템이 POSIX 권한을 알면 새로 만드는 디렉터리와 파일을 소유자만 쓰게 한다. API 키는 담지 않는다.
 */
public final class ExtractionCache {

    /** 키를 만드는 방식의 이름. 키에 넣는 값이나 순서·인코딩을 바꾸면 올려서 이전 키와 겹치지 않게 한다 */
    private static final String KEY_SCHEME = "h2spec-llm-extraction-cache-key-v1";
    /** 파일 형식의 버전. 모르는 형식의 파일은 없는 것으로 본다 */
    private static final int FORMAT = 1;
    private static final String SUFFIX = ".json";
    /** 저장하다 멈춘 임시 파일의 끝. {@link #prune}이 하루가 지난 것을 지운다 */
    private static final String TEMP_SUFFIX = ".tmp";
    /** 캐시 디렉터리에서 응답 파일로 보는 이름. 다른 파일은 지우지 않는다 */
    private static final Pattern ENTRY_FILE = Pattern.compile("[0-9a-f]{64}\\.json");
    /** 응답 파일을 저장할 때 만드는 임시 파일 이름({@code <응답 파일 이름>.<난수>.tmp}). 다른 {@code .tmp} 파일은 지우지 않는다 */
    private static final Pattern TEMP_FILE = Pattern.compile("[0-9a-f]{64}\\.json\\.-?\\d+\\.tmp");
    /** 이만큼 쓰지 않은 응답은 {@link #prune}이 지운다 */
    public static final Duration DEFAULT_MAX_IDLE = Duration.ofDays(30);
    private static final Set<PosixFilePermission> OWNER_ONLY_DIRECTORY = PosixFilePermissions.fromString("rwx------");
    private static final Set<PosixFilePermission> OWNER_ONLY_FILE = PosixFilePermissions.fromString("rw-------");
    private static final ObjectMapper MAPPER = new ObjectMapper();

    /** 캐시 디렉터리. 꺼져 있으면 null */
    private final Path directory;

    private ExtractionCache(Path directory) {
        this.directory = directory;
    }

    /** @return 찾지도 저장하지도 않는 캐시 ({@code --no-cache}) */
    public static ExtractionCache disabled() {
        return new ExtractionCache(null);
    }

    /**
     * @param directory 캐시 디렉터리 ({@code --cache-dir}). 없으면 처음 저장할 때 만든다
     * @return 그 디렉터리를 쓰는 캐시
     */
    public static ExtractionCache at(Path directory) {
        return new ExtractionCache(Objects.requireNonNull(directory, "directory"));
    }

    /**
     * @return 기본 캐시 디렉터리. {@code ${XDG_CACHE_HOME:-~/.cache}/h2spec/llm}, Windows는 {@code %LOCALAPPDATA%\h2spec\llm}
     */
    public static Path defaultDirectory() {
        return defaultDirectory(System.getenv(), System.getProperty("os.name", ""), System.getProperty("user.home", ""));
    }

    /** 테스트가 운영체제와 환경변수를 바꿔 볼 수 있게 받는다 */
    static Path defaultDirectory(Map<String, String> environment, String osName, String userHome) {
        if (osName.toLowerCase(Locale.ROOT).startsWith("windows")) {
            String localAppData = environment.get("LOCALAPPDATA");
            // 사용자마다 따로인 자리라 권한을 따로 좁히지 않아도 다른 사용자가 읽지 못한다. 변수가 없으면 그 기본 위치를 쓴다
            Path base = hasText(localAppData) ? Path.of(localAppData) : Path.of(userHome, "AppData", "Local");
            return base.resolve("h2spec").resolve("llm");
        }
        String cacheHome = environment.get("XDG_CACHE_HOME");
        // XDG 명세는 비었거나 절대 경로가 아닌 값을 무시하고 ~/.cache를 쓰라고 한다
        Path base = hasText(cacheHome) && Path.of(cacheHome).isAbsolute() ? Path.of(cacheHome) : Path.of(userHome, ".cache");
        return base.resolve("h2spec").resolve("llm");
    }

    /** @return 캐시를 쓰는지 */
    public boolean enabled() {
        return directory != null;
    }

    /** @return 캐시 디렉터리. 꺼져 있으면 빈 값 */
    public Optional<Path> directory() {
        return Optional.ofNullable(directory);
    }

    /**
     * 요청의 캐시 키를 만든다. 시스템 프롬프트 내용, 직렬화한 문서, 작업 지시, 스키마 내용, 회사, 모델, 추론 깊이, 출력 한도를 이 순서로 넣는다.
     * 작업 지시가 빠지면 검증 결과를 알려 다시 부르는 요청이 처음 요청과 같은 키가 되어 처음 응답을 돌려준다. 결과를 바꾸는 회사별 옵션이 늘면
     * 여기에 더하고 {@link #KEY_SCHEME}을 올린다.
     *
     * @param request  보낼 요청
     * @param provider 회사 ID
     * @param model    모델 ID
     * @param effort   추론 깊이 설정값
     * @return 캐시 키
     */
    public static Key key(LlmRequest request, String provider, String model, String effort) {
        MessageDigest digest = sha256();
        // 값마다 UTF-8 바이트 길이를 앞에 붙여 잇는다. 경계가 정해져 있어 ("ab", "c")와 ("a", "bc")처럼 이으면 같아지는 값끼리 키가 겹치지 않는다
        for (String part : List.of(KEY_SCHEME, request.systemPrompt(), request.document(), request.instruction(),
                request.jsonSchema(), Objects.requireNonNull(provider, "provider"), Objects.requireNonNull(model, "model"),
                Objects.requireNonNull(effort, "effort"), Integer.toString(request.maxOutputTokens()))) {
            byte[] bytes = part.getBytes(StandardCharsets.UTF_8);
            digest.update(ByteBuffer.allocate(Long.BYTES).putLong(bytes.length).array());
            digest.update(bytes);
        }
        return new Key(HexFormat.of().formatHex(digest.digest()));
    }

    /**
     * 저장해 둔 응답을 찾는다.
     *
     * @param key 캐시 키
     * @return 저장한 응답. 잘림 표시는 글이 비어 있고 {@link Finish#TRUNCATED}다. 없거나, 꺼져 있거나, 형식이 맞지 않는 파일이면 빈 값
     * @throws IOException 파일을 읽지 못했을 때
     */
    public Optional<LlmResponse> find(Key key) throws IOException {
        if (directory == null) {
            return Optional.empty();
        }
        Path file = file(key);
        byte[] content;
        try {
            content = Files.readAllBytes(file);
        } catch (NoSuchFileException e) {
            return Optional.empty();
        }
        Optional<LlmResponse> found = read(content);
        if (found.isPresent()) {
            touch(file);
        }
        return found;
    }

    /**
     * {@link #DEFAULT_MAX_IDLE}보다 오래 쓰지 않은 응답과, 저장하다 멈춘 지 하루가 지난 임시 파일을 지운다. 응답 파일 이름 꼴이 아닌
     * 파일은 건드리지 않는다. CLI가 llm 엔진으로 변환할 때 한 번 부른다.
     *
     * @param maxIdle 마지막으로 꺼내거나 저장한 뒤 이만큼 지난 응답을 지운다
     * @param now     지금 시각
     * @return 지운 응답 수. 꺼져 있거나 디렉터리가 없으면 0
     * @throws IOException 디렉터리를 읽거나 파일을 지우지 못했을 때
     */
    public int prune(Duration maxIdle, Instant now) throws IOException {
        if (directory == null || !Files.isDirectory(directory)) {
            return 0;
        }
        Instant idleBefore = now.minus(maxIdle);
        Instant tempBefore = now.minus(Duration.ofDays(1));
        int removed = 0;
        try (DirectoryStream<Path> files = Files.newDirectoryStream(directory)) {
            for (Path file : files) {
                String name = file.getFileName().toString();
                boolean entry = ENTRY_FILE.matcher(name).matches();
                // --cache-dir로 다른 파일이 있는 디렉터리를 줄 수 있어, 임시 파일도 저장할 때 만드는 이름 꼴만 지운다
                if ((!entry && !TEMP_FILE.matcher(name).matches()) || !Files.isRegularFile(file)) {
                    continue;
                }
                Instant modified = Files.getLastModifiedTime(file).toInstant();
                if (modified.isBefore(entry ? idleBefore : tempBefore) && Files.deleteIfExists(file) && entry) {
                    removed++;
                }
            }
        }
        return removed;
    }

    /** 쓰는 응답이 지워지지 않게 시각을 새로 한다. 못 해도 응답은 그대로 쓴다. 다음 정리에서 지워질 수 있을 뿐이다 */
    private static void touch(Path file) {
        try {
            Files.setLastModifiedTime(file, FileTime.from(Instant.now()));
        } catch (IOException e) {
            // 버린다
        }
    }

    /**
     * 응답을 저장한다. {@link Finish#COMPLETE}면 JSON 원본과 사용량을, {@link Finish#TRUNCATED}면 잘린 글과 잘림 표시와 사용량을
     * 저장하고, {@link Finish#REFUSED}는 저장하지 않는다. 같은 키의 파일이 있으면 바꾼다.
     *
     * @param key      캐시 키
     * @param response 받은 응답. COMPLETE면 중간 스키마로 읽히는지 부르는 쪽이 먼저 확인한다. 읽히지 않는 응답을 저장하면 다시 불러도 같은 것을 읽는다
     * @throws IOException 디렉터리나 파일을 쓰지 못했을 때
     */
    public void store(Key key, LlmResponse response) throws IOException {
        if (directory == null || response.finish() == Finish.REFUSED) {
            return;
        }
        ObjectNode entry = MAPPER.createObjectNode();
        entry.put("format", FORMAT);
        entry.put("finish", response.finish().name());
        entry.put("model", response.model());
        Usage usage = response.usage();
        entry.putObject("usage")
                .put("inputTokens", usage.inputTokens())
                .put("cacheReadInputTokens", usage.cacheReadInputTokens())
                .put("cacheWriteInputTokens", usage.cacheWriteInputTokens())
                .put("outputTokens", usage.outputTokens())
                .put("reasoningTokens", usage.reasoningTokens());
        // 검증·정규화 전의 원본 글 그대로다. 객체로 풀어 넣으면 다시 쓸 때 글이 바뀔 수 있다. 잘린 글은 JSON이 아니어도 그대로 둔다
        entry.put("json", response.json());
        write(file(key), MAPPER.writerWithDefaultPrettyPrinter().writeValueAsBytes(entry));
    }

    /** 손으로 고쳤거나 형식이 다른 파일은 없는 것으로 본다. 다음에 받은 응답이 덮어쓴다 */
    private static Optional<LlmResponse> read(byte[] content) {
        JsonNode entry;
        try {
            entry = MAPPER.readTree(content);
        } catch (IOException e) {
            return Optional.empty();
        }
        if (entry == null || entry.path("format").asInt() != FORMAT) {
            return Optional.empty();
        }
        Finish finish = switch (entry.path("finish").asText()) {
            case "COMPLETE" -> Finish.COMPLETE;
            case "TRUNCATED" -> Finish.TRUNCATED;
            default -> null;
        };
        JsonNode json = entry.path("json");
        JsonNode usage = entry.path("usage");
        if (finish == null || (finish == Finish.COMPLETE && !json.isTextual()) || !entry.path("model").isTextual()) {
            return Optional.empty();
        }
        List<String> counts = List.of("inputTokens", "cacheReadInputTokens", "cacheWriteInputTokens", "outputTokens",
                "reasoningTokens");
        if (!counts.stream().allMatch(name -> usage.path(name).canConvertToLong())) {
            return Optional.empty();
        }
        // 잘린 응답은 예전에는 글 없이 잘림 표시만 저장했다. 그런 파일은 잘린 글이 빈 것으로 읽는다
        return Optional.of(new LlmResponse(json.isTextual() ? json.asText() : "", entry.path("model").asText(),
                new Usage(usage.path("inputTokens").asLong(), usage.path("cacheReadInputTokens").asLong(),
                        usage.path("cacheWriteInputTokens").asLong(), usage.path("outputTokens").asLong(),
                        usage.path("reasoningTokens").asLong()),
                finish));
    }

    /** 같은 디렉터리의 임시 파일에 다 쓴 뒤 옮긴다. 옮기기 전에는 원래 파일이 그대로라 읽는 쪽이 반쯤 쓴 파일을 보지 않는다 */
    private void write(Path target, byte[] content) throws IOException {
        boolean posix = directory.getFileSystem().supportedFileAttributeViews().contains("posix");
        if (posix) {
            Files.createDirectories(directory, attributes(OWNER_ONLY_DIRECTORY));
        } else {
            Files.createDirectories(directory);
        }
        Path temp = posix
                ? Files.createTempFile(directory, target.getFileName() + ".", TEMP_SUFFIX, attributes(OWNER_ONLY_FILE))
                : Files.createTempFile(directory, target.getFileName() + ".", TEMP_SUFFIX);
        try {
            Files.write(temp, content);
            try {
                Files.move(temp, target, StandardCopyOption.ATOMIC_MOVE);
            } catch (AtomicMoveNotSupportedException e) {
                // 같은 디렉터리 안이라 대개 원자적으로 옮겨진다. 그렇게 못 하는 파일 시스템에서는 덮어쓰며 옮긴다
                Files.move(temp, target, StandardCopyOption.REPLACE_EXISTING);
            }
        } finally {
            Files.deleteIfExists(temp);
        }
    }

    private Path file(Key key) {
        return directory.resolve(key.sha256() + SUFFIX);
    }

    private static FileAttribute<Set<PosixFilePermission>> attributes(Set<PosixFilePermission> permissions) {
        return PosixFilePermissions.asFileAttribute(permissions);
    }

    private static MessageDigest sha256() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException e) {
            // 모든 Java 플랫폼이 SHA-256을 갖추도록 MessageDigest 명세가 정해 두어 일어나지 않는다
            throw new IllegalStateException(e);
        }
    }

    private static boolean hasText(String value) {
        return value != null && !value.isBlank();
    }

    /**
     * 결과 캐시 키. 파일 이름이 되므로 꼴을 검사한다.
     *
     * @param sha256 요청 전체의 SHA-256 (소문자 16진수 64자)
     */
    public record Key(String sha256) {

        private static final Pattern SHA256 = Pattern.compile("[0-9a-f]{64}");

        /** 파일 이름이 되므로 다른 꼴(경로 구분자 등)을 받으면 캐시 디렉터리 밖을 가리킬 수 있어 받지 않는다 */
        public Key {
            if (sha256 == null || !SHA256.matcher(sha256).matches()) {
                throw new IllegalArgumentException("결과 캐시 키는 소문자 16진수 64자여야 합니다: " + sha256);
            }
        }
    }
}
