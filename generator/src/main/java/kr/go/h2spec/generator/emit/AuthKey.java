package kr.go.h2spec.generator.emit;

import kr.go.h2spec.generator.ir.ApiAuth;
import kr.go.h2spec.generator.ir.ApiSpec;
import kr.go.h2spec.generator.ir.RequestParameter;

import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * 생성 클라이언트가 인증키를 넣는 자리. IR의 api.auth로 정한다. api.auth가 없으면(api.auth가 생기기 전의 IR과 rule 엔진의 IR)
 * 요청 파라미터의 이름으로 정한다({@link #inferred}). 생성자로 받은 키를 넣으므로 그 파라미터는 메서드 인자에서 빠진다.
 *
 * @param placement   넣는 자리
 * @param name        질의 파라미터·경로 변수·헤더의 이름. 문서 표기 그대로다. 키가 없으면 null
 * @param description 생성자 javadoc에 쓸 설명. 헤더는 요청 파라미터가 없어 코드가 붙인 설명이다. 키가 없으면 null
 */
public record AuthKey(Placement placement, String name, String description) {

    /** {@link #NONE}은 인증키를 보내지 않는다. 키 없이 여는 API(지자체 자체 API 등)와 키 파라미터를 찾지 못한 IR이다 */
    public enum Placement { QUERY, PATH, HEADER, NONE }

    /** api.auth가 없을 때 인증키로 보는 파라미터 이름. 문서마다 대소문자가 달라(serviceKey / ServiceKey) 무시하고 비교한다 */
    static final String SERVICE_KEY_PARAM = "serviceKey";
    /**
     * api.auth가 없고 serviceKey 파라미터도 없을 때 인증키로 보는 질의 파라미터 이름. 대소문자와 _·-를 무시하고 비교한다.
     * 공공데이터 문서에 실제로 나오는 이름이다(농사로 apiKey, 제주 authApiKey, OpenDART crtfc_key 등).
     * 이름이 key로 끝나도 검색어·정렬 키(searchKey, sortKey, bookKey)는 인증키가 아니라 목록에 있는 이름만 본다
     */
    private static final Set<String> QUERY_KEY_NAMES = Set.of(
            "apikey", "authapikey", "authkey", "certkey", "crtfckey", "accesskey", "appkey", "openapikey", "key");
    /** 인증키로 보는 경로 변수 이름({your_appkey}, {your_projectKey}, {KEY}). 대소문자를 무시한다 */
    private static final Pattern PATH_KEY_NAME =
            Pattern.compile("(?i)(your[_-]?)?((app|api|project|service|auth|access|cert|open[_-]?api)[_-]?)?key");
    /** 헤더 이름에 쓸 수 있는 글자(RFC 9110의 token). 이름은 생성 코드의 문자열 리터럴과 요청 헤더에 그대로 들어간다 */
    private static final Pattern HEADER_NAME = Pattern.compile("[!#$%&'*+.^_`|~0-9A-Za-z-]+");

    /**
     * IR의 인증키 자리를 정한다. api.auth가 없으면 {@link #inferred}로 정한다.
     *
     * @throws IllegalArgumentException api.auth가 가리키는 자리가 IR에 없거나 쓸 수 없을 때
     */
    public static AuthKey of(ApiSpec api) {
        ApiAuth auth = api.auth();
        if (auth == null) {
            return inferred(api);
        }
        if (auth.name() == null || auth.name().isBlank()) {
            throw new IllegalArgumentException("api.auth.name이 없습니다 — 인증키를 넣을 파라미터나 헤더의 이름을 적어 주세요.");
        }
        String kind = String.valueOf(auth.kind());
        return switch (kind) {
            case ApiAuth.QUERY -> new AuthKey(Placement.QUERY, auth.name(), parameter(api, auth, "query").description());
            case ApiAuth.PATH -> {
                if (!api.endpoint().contains("{" + auth.name() + "}")) {
                    throw new IllegalArgumentException("api.auth가 가리키는 경로 변수가 endpoint에 없습니다: '"
                            + JavaText.literal(auth.name()) + "'");
                }
                yield new AuthKey(Placement.PATH, auth.name(), parameter(api, auth, "path").description());
            }
            case ApiAuth.HEADER -> {
                if (!isHeaderName(auth.name())) {
                    throw new IllegalArgumentException("api.auth의 헤더 이름에 쓸 수 없는 글자가 있습니다: '"
                            + JavaText.literal(auth.name()) + "' — 영문자·숫자와 !#$%&'*+.^_`|~- 만 쓸 수 있습니다.");
                }
                yield new AuthKey(Placement.HEADER, auth.name(), "요청 헤더 " + auth.name() + "로 보내는 인증키");
            }
            default -> throw new IllegalArgumentException("지원하지 않는 api.auth.kind입니다: '" + JavaText.literal(kind)
                    + "' — " + ApiAuth.QUERY + ", " + ApiAuth.PATH + ", " + ApiAuth.HEADER + " 가운데 하나여야 합니다.");
        };
    }

    /**
     * 생성자로 받는 인증키 파라미터인지. 이 파라미터는 메서드 인자가 되지 않고 OpenAPI 문서에도 파라미터로 적지 않는다(경로 변수는 적는다).
     * 생성 코드의 인자 이름을 검사하기 전에 부르므로 IR이 틀려도 실패하지 않는다. 틀린 IR은 {@link #of}가 거부한다
     */
    public static boolean isKey(ApiSpec api, RequestParameter p) {
        ApiAuth auth = api.auth();
        if (auth == null) {
            if (SERVICE_KEY_PARAM.equalsIgnoreCase(p.name())) {
                return true;
            }
            AuthKey key = inferred(api);
            return key.placement() != Placement.NONE && key.name().equals(p.name())
                    && (key.placement() == Placement.QUERY ? "query" : "path").equals(p.in());
        }
        return auth.name() != null && auth.name().equals(p.name())
                && ((ApiAuth.QUERY.equals(auth.kind()) && "query".equals(p.in()))
                || (ApiAuth.PATH.equals(auth.kind()) && "path".equals(p.in())));
    }

    /** 인증키를 넣는 헤더의 이름으로 쓸 수 있는지(RFC 9110의 token) */
    public static boolean isHeaderName(String name) {
        return name != null && HEADER_NAME.matcher(name).matches();
    }

    /**
     * 생성 코드가 키 값으로 가려야 하는지. 이름이 serviceKey인 질의는 인터셉터와 {@code ServiceKeyMasker.mask(String)}가 이름으로
     * 찾아 가리므로 api.auth가 생기기 전과 같은 코드를 만든다. 그 밖의 자리는 이름으로 찾을 수 없어 키 값을 넘겨 가린다
     */
    public boolean masksByValue() {
        return placement != Placement.NONE
                && (placement != Placement.QUERY || !SERVICE_KEY_PARAM.equalsIgnoreCase(name));
    }

    /** 인증키를 받는지. {@link Placement#NONE}이면 생성 클라이언트가 키를 받지 않는다 */
    public boolean hasKey() {
        return placement != Placement.NONE;
    }

    /**
     * api.auth가 없는 IR의 인증키 자리를 요청 파라미터의 이름으로 정한다. 실패하지 않는다.
     * <ol>
     *   <li>이름이 serviceKey(대소문자 무시)인 파라미터를 질의로 보낸다. api.auth가 생기기 전과 같다</li>
     *   <li>이름이 알려진 인증키 이름({@link #QUERY_KEY_NAMES})인 질의 파라미터</li>
     *   <li>이름이 인증키 꼴({@link #PATH_KEY_NAME})인 경로 변수</li>
     *   <li>없으면 인증키를 보내지 않는다. 키 없이 여는 API이거나 문서에서 키 파라미터를 찾지 못한 IR이다.
     *       키가 빠졌으면 기관이 오류 코드로 답하고 인터셉터가 예외로 알린다</li>
     * </ol>
     * 각 단계 안에서는 문서에 적힌 차례대로 처음 맞는 파라미터를 쓴다
     */
    static AuthKey inferred(ApiSpec api) {
        for (RequestParameter p : api.requestParameters()) {
            if (SERVICE_KEY_PARAM.equalsIgnoreCase(p.name())) {
                return new AuthKey(Placement.QUERY, p.name(), p.description());
            }
        }
        for (RequestParameter p : api.requestParameters()) {
            if ("query".equals(p.in()) && p.name() != null
                    && QUERY_KEY_NAMES.contains(p.name().replaceAll("[_-]", "").toLowerCase(Locale.ROOT))) {
                return new AuthKey(Placement.QUERY, p.name(), p.description());
            }
        }
        for (RequestParameter p : api.requestParameters()) {
            if ("path".equals(p.in()) && p.name() != null && PATH_KEY_NAME.matcher(p.name()).matches()
                    && api.endpoint().contains("{" + p.name() + "}")) {
                return new AuthKey(Placement.PATH, p.name(), p.description());
            }
        }
        return new AuthKey(Placement.NONE, null, null);
    }

    private static RequestParameter parameter(ApiSpec api, ApiAuth auth, String in) {
        for (RequestParameter p : api.requestParameters()) {
            if (auth.name().equals(p.name()) && in.equals(p.in())) {
                return p;
            }
        }
        throw new IllegalArgumentException("api.auth가 가리키는 " + in + " 파라미터가 requestParameters에 없습니다: '"
                + JavaText.literal(auth.name()) + "'");
    }
}
