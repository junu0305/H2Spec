package kr.go.h2spec.generator.emit;

import kr.go.h2spec.generator.ir.GeneratorHints;
import kr.go.h2spec.generator.ir.IrSpec;
import kr.go.h2spec.generator.ir.RequestParameter;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/** IR을 RestTemplate 기반 API 클라이언트 단일 .java 소스로 방출한다. */
public class ClientEmitter {

    private static final String INDENT = "    ";
    /** 생성자로 받은 인증키를 담는 필드. 문서의 인증키 이름과 상관없이 이 이름이다 */
    private static final String KEY_FIELD = "serviceKey";
    /** 응답 포맷을 고르는 요청 파라미터. 이름이 기관마다 다르다 */
    private static final Set<String> RESPONSE_FORMAT_PARAMS = Set.of("returnType", "dataType", "resultType");
    /**
     * API 메서드 본문이 이미 쓰는 지역 변수와 필드. 인자의 Java 이름이 지역 변수와 같으면 "already defined"로,
     * 필드와 같으면 필드가 인자에 가려져 컴파일되지 않는다. 매퍼 필드는 포맷에 따라 하나만 쓰므로 따로 본다.
     */
    private static final Set<String> METHOD_BODY_NAMES = Set.of("url", "uri", "body", "e", "restTemplate");
    /** 엔드포인트로 만든 메서드명이 식별자가 아닐 때 앞에 붙인다. 메서드는 모두 API를 부르는 일이라 동사 call을 쓴다 */
    private static final String METHOD_NAME_PREFIX = "call";
    /**
     * 생성 클래스가 이미 갖는 메서드(생성하는 헬퍼와 Object의 메서드)의 이름과 인자 타입. 엔드포인트로 만든 메서드가 이와 같으면
     * 컴파일되지 않으므로({@code /encode}에 문자열 인자 하나, 인자 없는 {@code /getClass}) 접두사를 붙인다. 인자가 다르면 겹치지 않아 그대로 둔다.
     * 질의 구분자 헬퍼({@code separator(StringBuilder)})는 메서드 인자에 StringBuilder가 없어 겹치지 않는다
     */
    private static final Set<String> RESERVED_SIGNATURES = Set.of("encode(String)",
            "getClass()", "hashCode()", "toString()", "notify()", "notifyAll()", "wait()", "finalize()");
    /** 경로 변수가 있으면 생성하는 헬퍼 */
    private static final String ENCODE_PATH_SIGNATURE = "encodePath(String)";
    /** 인증키를 질의로 보내면 생성하는 헬퍼 */
    private static final String ENCODE_KEY_SIGNATURE = "encodeKey(String)";
    /** RestTemplate을 만드는 헬퍼. 키 값으로 가리는 클라이언트는 키를 받아 인터셉터에 넘긴다 */
    private static final String DEFAULT_REST_TEMPLATE_SIGNATURE = "defaultRestTemplate()";
    private static final String KEYED_REST_TEMPLATE_SIGNATURE = "defaultRestTemplate(String)";
    /**
     * URL의 질의에 그대로 쓸 수 있는 글자({@code URI.create}가 받는 글자 가운데 질의를 깨지 않는 것). 그 밖의 ASCII 글자(공백,
     * {@code & = + # %} 등)는 파라미터 이름을 질의에 넣을 때 퍼센트 인코딩한다. 한글 같은 비ASCII 글자는 {@code URI.create}가 받으므로
     * 그대로 둔다(지금 생성 코드가 바뀌지 않게)
     */
    private static final String QUERY_SAFE = "-._~!$'()*,;:@/?[]";
    /**
     * interceptor-core의 PublicDataErrorDetector가 기본으로 찾는 결과코드 이름. 이 밖의 이름만 인터셉터에 넘겨서,
     * 기본 이름만 있는 IR(rule 엔진의 IR은 모두 그렇다)은 성공코드만 넘기는 코드를 만든다.
     * generator는 interceptor-core에 기대지 않아 목록을 따로 두고, 인터셉터가 실제로 이 이름을 찾는지는 테스트가 본다.
     */
    static final Set<String> BUILT_IN_RESULT_CODE_KEYS =
            Set.of("resultCode", "RESULT_CODE", "returnReasonCode", "errorCode", "ERROR_CODE");
    private static final String LIST_SUFFIX = "[]";

    private static final Map<String, String> TYPE_MAP = Map.of(
            "string", "String",
            "integer", "Long",
            "number", "Double",
            "boolean", "Boolean");
    private static final String DEFAULT_TYPE = "String";

    /**
     * @return 힌트 없이 IR의 응답 형식을 값으로 채워 보내는 포맷 파라미터 이름. parser의 같은 목록({@code IrConventions})과 같아야 한다
     */
    public static Set<String> responseFormatParameters() {
        return RESPONSE_FORMAT_PARAMS;
    }

    public String emit(IrSpec ir) {
        String className = JavaNames.pascal(ir.api().apiId()) + "Client";
        String responseClassName = JavaNames.pascal(ir.api().apiId()) + "Response";
        boolean isXml = "XML".equals(ir.api().responseFormat());
        AuthKey key = AuthKey.of(ir.api());
        List<RequestParameter> params = methodParameters(ir);
        validateNoClashWithMethodBody(params, mapperVar(isXml));
        List<String> resultCodeKeys = extraResultCodeKeys(ir);

        StringBuilder sb = new StringBuilder();
        sb.append("package ").append(ir.generatorHints().targetPackage()).append(";\n\n");
        appendImports(sb, ir, responseClassName, isXml, !resultCodeKeys.isEmpty() || key.masksByValue(), key);
        appendClassJavadoc(sb, ir, key);
        sb.append("public class ").append(className).append(" {\n\n");
        appendFields(sb, ir, isXml, key);
        sb.append("\n");
        appendConstructors(sb, className, key);
        sb.append("\n");
        appendDefaultRestTemplateFactory(sb, resultCodeKeys, key.masksByValue());
        sb.append("\n");
        appendApiMethod(sb, ir, params, responseClassName, isXml, key);
        sb.append("\n");
        boolean keyInQuery = key.placement() == AuthKey.Placement.QUERY;
        appendEncodeHelper(sb, keyInQuery, hasPathParameter(ir), !keyInQuery && hasQueryItems(ir, params));
        sb.append("}\n");
        return sb.toString();
    }

    /**
     * API 메서드의 인자가 되는 요청 파라미터(isHidden인 것은 빠진다). 이 파라미터들만 Java 이름이 변수로 쓰여서,
     * CodeGenerator가 인자 이름의 식별자 여부와 겹침을 검사할 때도 이 목록을 쓴다.
     */
    public static List<RequestParameter> methodParameters(IrSpec ir) {
        String formatParam = formatParamName(ir);
        List<RequestParameter> result = new ArrayList<>();
        for (RequestParameter p : ir.api().requestParameters()) {
            if (!isHidden(ir, p, formatParam)) {
                result.add(p);
            }
        }
        return result;
    }

    /**
     * 메서드 시그니처에서 감추는 파라미터. 인증키는 생성자로 받고, 포맷 파라미터는
     * 생성 시점에 확정한 값으로 박는다 — 호출자가 매퍼와 어긋나는 값을 넣을 수 없게 한다.
     */
    private static boolean isHidden(IrSpec ir, RequestParameter p, String formatParam) {
        return AuthKey.isKey(ir.api(), p) || RESPONSE_FORMAT_PARAMS.contains(p.name())
                || p.name().equals(formatParam);
    }

    private static String mapperVar(boolean isXml) {
        return isXml ? "xmlMapper" : "objectMapper";
    }

    private void validateNoClashWithMethodBody(List<RequestParameter> params, String mapperVar) {
        for (RequestParameter p : params) {
            String varName = JavaNames.camel(p.name());
            String compared = JavaNameRules.canonicalIdentifier(varName);
            if (METHOD_BODY_NAMES.contains(compared) || mapperVar.equals(compared)) {
                throw new IllegalArgumentException(
                        "요청 파라미터의 Java 이름이 생성 메서드의 변수와 겹칩니다: '" + p.name() + "' → '" + varName
                        + "' — 요청 파라미터 이름을 바꿔 주세요.");
            }
        }
    }

    /**
     * IR의 결과코드 위치 후보에서 인터셉터에 넘길 이름을 뽑는다. 인터셉터는 위치가 아니라 이름으로 결과코드를 찾으므로
     * 후보 경로의 마지막 마디를 쓴다(response.header.resultCd → resultCd). 기본 이름과 이미 뽑은 이름은 뺀다.
     */
    private static List<String> extraResultCodeKeys(IrSpec ir) {
        Set<String> keys = new LinkedHashSet<>();
        for (String candidate : ir.api().errorSpec().resultCodeFieldCandidates()) {
            String key = lastPathSegment(candidate);
            if (!key.isBlank() && !BUILT_IN_RESULT_CODE_KEYS.contains(key)) {
                keys.add(key);
            }
        }
        return List.copyOf(keys);
    }

    /** 응답 경로 표기의 마지막 마디. 끝의 []는 이름이 아니라 리스트 표시라 뗀다(DtoTreeBuilder와 같은 표기) */
    private static String lastPathSegment(String path) {
        if (path == null) {
            return "";
        }
        String segment = path.substring(path.lastIndexOf('.') + 1);
        return segment.endsWith(LIST_SUFFIX) ? segment.substring(0, segment.length() - LIST_SUFFIX.length()) : segment;
    }

    /**
     * 문서에 포맷 파라미터가 있으면 그 이름을 돌려준다. 없으면 null.
     * generatorHints가 가리키는 파라미터가 requestParameters에 있으면 그것을 먼저 쓴다.
     */
    private static String formatParamName(IrSpec ir) {
        GeneratorHints.ResponseFormatParameter hint = formatHint(ir);
        if (hint != null) {
            return hint.name();
        }
        for (RequestParameter p : ir.api().requestParameters()) {
            if (RESPONSE_FORMAT_PARAMS.contains(p.name())) {
                return p.name();
            }
        }
        return null;
    }

    /** 포맷 파라미터에 넣을 값. 힌트에 값이 있으면 그 값, 없으면 responseFormat을 소문자로 */
    private static String formatParamValue(IrSpec ir) {
        GeneratorHints.ResponseFormatParameter hint = formatHint(ir);
        if (hint != null && hint.value() != null && !hint.value().isBlank()) {
            return hint.value();
        }
        return ir.api().responseFormat().toLowerCase(Locale.ROOT);
    }

    /** requestParameters에 있는 파라미터를 가리키는 힌트만 쓴다. 없으면 null */
    private static GeneratorHints.ResponseFormatParameter formatHint(IrSpec ir) {
        GeneratorHints.ResponseFormatParameter hint =
                ir.generatorHints() == null ? null : ir.generatorHints().responseFormatParameter();
        if (hint == null || hint.name() == null) {
            return null;
        }
        for (RequestParameter p : ir.api().requestParameters()) {
            if (hint.name().equals(p.name())) {
                return hint;
            }
        }
        return null;
    }

    // java.* 그룹 → jackson/spring 그룹(XmlMapper 또는 ObjectMapper 선택) → 프로젝트 내부 그룹. 그룹마다 빈 줄.
    private void appendImports(StringBuilder sb, IrSpec ir, String responseClassName, boolean isXml,
                               boolean usesList, AuthKey key) {
        sb.append("import java.io.IOException;\n");
        sb.append("import java.net.URI;\n");
        sb.append("import java.net.URLEncoder;\n");
        sb.append("import java.nio.charset.StandardCharsets;\n");
        if (usesList) {
            sb.append("import java.util.List;\n");
        }
        sb.append("\n");

        if (isXml) {
            sb.append("import com.fasterxml.jackson.dataformat.xml.XmlMapper;\n");
        } else {
            sb.append("import com.fasterxml.jackson.databind.DeserializationFeature;\n");
            sb.append("import com.fasterxml.jackson.databind.ObjectMapper;\n");
        }
        if (key.placement() == AuthKey.Placement.HEADER) {
            sb.append("import org.springframework.http.RequestEntity;\n");
        }
        sb.append("import org.springframework.http.client.BufferingClientHttpRequestFactory;\n");
        sb.append("import org.springframework.http.client.SimpleClientHttpRequestFactory;\n");
        sb.append("import org.springframework.web.client.RestTemplate;\n");
        sb.append("\n");

        sb.append("import kr.go.h2spec.client.interceptor.PublicDataErrorInterceptor;\n");
        sb.append("import kr.go.h2spec.client.interceptor.ServiceKeyMasker;\n");
        sb.append("import ").append(ir.generatorHints().targetPackage()).append(".dto.")
          .append(responseClassName).append(";\n");
        sb.append("\n");
    }

    private void appendClassJavadoc(StringBuilder sb, IrSpec ir, AuthKey key) {
        sb.append("/**\n");
        sb.append(" * ").append(JavaText.comment(ir.api().apiName())).append(" 클라이언트.\n");
        if (!key.hasKey()) {
            // 키가 필요한데 문서에서 키 파라미터를 찾지 못했을 수도 있다. 그러면 기관의 오류 코드를 인터셉터가 예외로 알린다
            sb.append(" * 명세에 인증키 파라미터가 없어 인증키 없이 부릅니다.\n");
        }
        sb.append(" * H2Spec generator가 자동 생성한 코드입니다. 직접 수정하지 마세요.\n");
        sb.append(" */\n");
    }

    private void appendFields(StringBuilder sb, IrSpec ir, boolean isXml, AuthKey key) {
        sb.append(INDENT).append("private static final String BASE_URL = \"")
          .append(JavaText.literal(ir.api().baseUrl())).append("\";\n");
        sb.append(INDENT).append("private static final String SUCCESS_CODE = \"")
          .append(JavaText.literal(ir.api().errorSpec().successResultCode())).append("\";\n");
        sb.append("\n");
        sb.append(INDENT).append("private final RestTemplate restTemplate;\n");
        if (isXml) {
            sb.append(INDENT).append("private final XmlMapper xmlMapper = new XmlMapper();\n");
        } else {
            sb.append(INDENT).append("private final ObjectMapper objectMapper = new ObjectMapper()\n");
            // DTO의 @JsonRootName과 짝. 공공데이터 JSON 응답은 {"response":{...}}처럼 루트를 키로 감싼다.
            // 맨 바깥에 필드가 바로 오는 응답({"header": …, "body": …})은 DTO가 응답 전체라 풀 루트가 없다
            if (!ir.api().responseRootless()) {
                sb.append(INDENT.repeat(3)).append(".enable(DeserializationFeature.UNWRAP_ROOT_VALUE)\n");
            }
            // XML을 JSON으로 옮겨 주는 기관은 결과가 1건이면 배열 대신 객체({"item": {…}})를, 없으면 빈 글({"items": ""})을 준다.
            // XML의 반복 요소는 건수와 상관없이 같은 모양이라 XmlMapper에는 필요 없다
            sb.append(INDENT.repeat(3)).append(".enable(DeserializationFeature.ACCEPT_SINGLE_VALUE_AS_ARRAY)\n");
            sb.append(INDENT.repeat(3)).append(".enable(DeserializationFeature.ACCEPT_EMPTY_STRING_AS_NULL_OBJECT);\n");
        }
        if (key.hasKey()) {
            sb.append(INDENT).append("private final String serviceKey;\n");
        }
    }

    private void appendConstructors(StringBuilder sb, String className, AuthKey key) {
        String indent2 = INDENT.repeat(2);
        if (!key.hasKey()) {
            sb.append(INDENT).append("public ").append(className).append("() {\n");
            sb.append(indent2).append("this(defaultRestTemplate());\n");
            sb.append(INDENT).append("}\n");
            sb.append("\n");
            sb.append(INDENT).append("public ").append(className).append("(RestTemplate restTemplate) {\n");
            sb.append(indent2).append("this.restTemplate = restTemplate;\n");
            sb.append(INDENT).append("}\n");
            return;
        }
        sb.append(INDENT).append("/**\n");
        sb.append(INDENT).append(" * @param serviceKey ").append(JavaText.comment(key.description()))
          .append("\n");
        sb.append(INDENT).append(" */\n");
        sb.append(INDENT).append("public ").append(className).append("(String serviceKey) {\n");
        sb.append(indent2).append(key.masksByValue()
                ? "this(serviceKey, defaultRestTemplate(serviceKey));\n"
                : "this(serviceKey, defaultRestTemplate());\n");
        sb.append(INDENT).append("}\n");
        sb.append("\n");
        sb.append(INDENT).append("public ").append(className).append("(String serviceKey, RestTemplate restTemplate) {\n");
        sb.append(indent2).append("this.serviceKey = serviceKey;\n");
        sb.append(indent2).append("this.restTemplate = restTemplate;\n");
        sb.append(INDENT).append("}\n");
    }

    // 결과코드 이름은 상수로 두지 않고 인자에 바로 쓴다. List 상수는 static 초기화 블록을 만들어서,
    // 생성 클라이언트에 static 블록이 없음을 보는 안전장치 테스트(CodeGeneratorSafetyTest)가 끼워 넣은 코드와 구별하지 못한다.
    // 키 값으로 가리는 클라이언트는 키를 받아 인터셉터에 넘긴다. 인터셉터가 오류 응답의 예외에서 키를 가린다
    // 결과코드 이름이나 키를 넘기는 PublicDataErrorInterceptor 생성자와 ServiceKeyMasker.mask(String, String)은
    // interceptor-core v0.2.0부터 개발되었다.
    private void appendDefaultRestTemplateFactory(StringBuilder sb, List<String> resultCodeKeys, boolean masksByValue) {
        String indent2 = INDENT.repeat(2);
        String indent4 = INDENT.repeat(4);
        sb.append(INDENT).append(masksByValue
                ? "private static RestTemplate defaultRestTemplate(String serviceKey) {\n"
                : "private static RestTemplate defaultRestTemplate() {\n");
        sb.append(indent2).append("RestTemplate restTemplate = new RestTemplate(\n");
        sb.append(indent4).append("new BufferingClientHttpRequestFactory(new SimpleClientHttpRequestFactory()));\n");
        sb.append(indent2).append("restTemplate.getInterceptors().add(new PublicDataErrorInterceptor(SUCCESS_CODE");
        if (!resultCodeKeys.isEmpty() || masksByValue) {
            sb.append(", List.of(");
            for (int i = 0; i < resultCodeKeys.size(); i++) {
                if (i > 0) {
                    sb.append(", ");
                }
                sb.append("\"").append(JavaText.literal(resultCodeKeys.get(i))).append("\"");
            }
            sb.append(")");
        }
        if (masksByValue) {
            sb.append(", serviceKey");
        }
        sb.append("));\n");
        sb.append(indent2).append("return restTemplate;\n");
        sb.append(INDENT).append("}\n");
    }

    /**
     * 엔드포인트를 메서드명으로 바꾼다. "/items/{itemId}/detail"처럼 세그먼트가 여럿이거나
     * path 파라미터 중괄호가 있으면 그대로는 자바 식별자가 되지 않아 낱말 경계로 다룬다.
     * 그렇게 만든 이름이 식별자가 아닐 때만 고친다. 지금 컴파일되는 이름은 바꾸지 않는다.
     * 식별자에 못 쓰는 글자("/getMsrstnList.do"의 ".")는 _로 바꾸고, 그래도 숫자로 시작하거나
     * 예약어이거나 비면 접두사를 붙인다("/7ttt…/{your_appkey}" → "call7ttt…YourAppkey", "/new" → "callNew").
     */
    private String methodNameOf(String endpoint) {
        String words = endpoint.replaceAll("[/{}]+", "_").replaceAll("^_+|_+$", "");
        String name = JavaNames.camelKeepingSymbols(words);
        if (JavaNameRules.isValidIdentifier(name)) {
            return name;
        }
        StringBuilder repaired = new StringBuilder(name.length());
        name.codePoints().forEach(cp -> repaired.appendCodePoint(
                Character.isJavaIdentifierPart(cp) && !Character.isIdentifierIgnorable(cp) ? cp : '_'));
        String candidate = repaired.toString();
        if (JavaNameRules.isValidIdentifier(candidate)) {
            return candidate;
        }
        return candidate.isEmpty()
                ? METHOD_NAME_PREFIX
                : METHOD_NAME_PREFIX + Character.toUpperCase(candidate.charAt(0)) + candidate.substring(1);
    }

    private void appendApiMethod(StringBuilder sb, IrSpec ir, List<RequestParameter> params,
                                  String responseClassName, boolean isXml, AuthKey key) {
        String indent2 = INDENT.repeat(2);
        String indent3 = INDENT.repeat(3);
        String methodName = methodNameOf(ir.api().endpoint());
        if (clashesWithExistingMethod(methodName, params, hasPathParameter(ir), key)) {
            methodName = METHOD_NAME_PREFIX + Character.toUpperCase(methodName.charAt(0)) + methodName.substring(1);
        }

        sb.append(INDENT).append("/**\n");
        sb.append(INDENT).append(" * ").append(JavaText.comment(ir.api().description())).append("\n");
        sb.append(INDENT).append(" *\n");
        for (RequestParameter p : params) {
            String suffix = p.required() ? "" : " (null이면 생략)";
            sb.append(INDENT).append(" * @param ").append(JavaNames.camel(p.name())).append(" ")
              .append(JavaText.comment(p.description())).append(suffix).append("\n");
        }
        sb.append(INDENT).append(" */\n");

        sb.append(INDENT).append("public ").append(responseClassName).append(" ").append(methodName).append("(\n");
        sb.append(indent3);
        for (int i = 0; i < params.size(); i++) {
            if (i > 0) {
                sb.append(", ");
            }
            RequestParameter p = params.get(i);
            sb.append(javaType(p.type())).append(" ").append(JavaNames.camel(p.name()));
        }
        sb.append(") {\n");

        sb.append(indent2).append("StringBuilder url = new StringBuilder(BASE_URL + \"")
          .append(JavaText.literal(ir.api().endpoint())).append("\");\n");
        // 메서드 인자의 Java 이름이 serviceKey(SERVICE_KEY 같은 다른 파라미터)면 필드를 가리므로 필드를 this로 가리킨다
        boolean keyShadowed = params.stream().anyMatch(p -> KEY_FIELD.equals(
                JavaNameRules.canonicalIdentifier(JavaNames.camel(p.name()))));
        String keyRef = (keyShadowed ? "this." : "") + KEY_FIELD;
        boolean keyInQuery = key.placement() == AuthKey.Placement.QUERY;
        if (keyInQuery) {
            sb.append(indent2).append("url.append(\"?").append(JavaText.literal(queryName(key.name())))
              .append("=\").append(encodeKey(").append(keyRef).append("));\n");
        } else if (key.placement() == AuthKey.Placement.PATH) {
            sb.append(indent2).append("url = new StringBuilder(url.toString().replace(\"{")
              .append(JavaText.literal(key.name())).append("}\", encodePath(").append(keyRef).append(")));\n");
        }
        // 키가 질의에 없으면 어느 파라미터가 처음 붙을지 생성할 때 모른다(선택 파라미터는 null이면 붙지 않는다)
        String queryAppend = keyInQuery ? "url.append(\"&" : "url.append(separator(url)).append(\"";
        String formatParam = formatParamName(ir);
        if (formatParam != null) {
            String formatValue = formatParamValue(ir);
            sb.append(indent2).append(queryAppend).append(JavaText.literal(queryName(formatParam))).append("=")
              .append(JavaText.literal(formatValue)).append("\");\n");
        }
        for (RequestParameter p : params) {
            if ("path".equals(p.in())) {
                String varName = JavaNames.camel(p.name());
                sb.append(indent2).append("url = new StringBuilder(url.toString().replace(\"{")
                  .append(JavaText.literal(p.name())).append("}\", encodePath(String.valueOf(").append(varName)
                  .append("))));\n");
                continue;
            }
            String varName = JavaNames.camel(p.name());
            String valueExpr = "string".equals(p.type()) ? "encode(" + varName + ")" : varName;
            String appendLine = queryAppend + JavaText.literal(queryName(p.name())) + "=\").append(" + valueExpr + ");";
            if (p.required()) {
                sb.append(indent2).append(appendLine).append("\n");
            } else {
                sb.append(indent2).append("if (").append(varName).append(" != null) {\n");
                sb.append(indent3).append(appendLine).append("\n");
                sb.append(indent2).append("}\n");
            }
        }
        sb.append(indent2).append("URI uri = URI.create(url.toString());\n");
        if (key.placement() == AuthKey.Placement.HEADER) {
            sb.append(indent2).append("byte[] body = restTemplate.exchange(\n");
            sb.append(INDENT.repeat(4)).append("RequestEntity.get(uri).header(\"").append(JavaText.literal(key.name()))
              .append("\", ").append(keyRef).append(").build(), byte[].class).getBody();\n");
        } else {
            sb.append(indent2).append("byte[] body = restTemplate.getForObject(uri, byte[].class);\n");
        }
        sb.append(indent2).append("try {\n");
        String mapperVar = mapperVar(isXml);
        sb.append(indent3).append("return ").append(mapperVar).append(".readValue(body, ")
          .append(responseClassName).append(".class);\n");
        sb.append(indent2).append("} catch (IOException e) {\n");
        // 파싱 실패 예외에도 인증키가 남지 않도록 URI를 마스킹해서 넣는다
        String masked = key.masksByValue()
                ? "ServiceKeyMasker.mask(uri.toString(), " + keyRef + ")"
                : "ServiceKeyMasker.mask(uri.toString())";
        sb.append(indent3).append("throw new IllegalStateException(\"응답 파싱 실패: \" + ").append(masked).append(", e);\n");
        sb.append(indent2).append("}\n");
        sb.append(INDENT).append("}\n");
    }

    /** 엔드포인트로 만든 메서드가 생성 클래스의 헬퍼나 Object의 메서드와 이름·인자 타입이 같은지 */
    private boolean clashesWithExistingMethod(String methodName, List<RequestParameter> params, boolean hasPathParameter,
                                              AuthKey key) {
        String signature = methodName + "(" + String.join(",", params.stream().map(p -> javaType(p.type())).toList()) + ")";
        return RESERVED_SIGNATURES.contains(signature) || (hasPathParameter && ENCODE_PATH_SIGNATURE.equals(signature))
                || (key.placement() == AuthKey.Placement.QUERY && ENCODE_KEY_SIGNATURE.equals(signature))
                || (key.masksByValue() ? KEYED_REST_TEMPLATE_SIGNATURE : DEFAULT_REST_TEMPLATE_SIGNATURE).equals(signature);
    }

    /**
     * 요청 파라미터 이름을 질의에 넣을 꼴로 바꾼다. 공백 같은 글자가 그대로 들어가면 컴파일은 되지만 호출할 때 {@code URI.create}가
     * 실패하고, {@code & = #}이 그대로 들어가면 질의가 깨진다. 영문자·숫자와 질의에 쓸 수 있는 글자, 비ASCII 글자는 그대로 둔다
     */
    static String queryName(String name) {
        StringBuilder sb = new StringBuilder(name.length());
        name.codePoints().forEach(cp -> {
            if (cp > 0x7f || Character.isLetterOrDigit(cp) || QUERY_SAFE.indexOf(cp) >= 0) {
                sb.appendCodePoint(cp);
            } else {
                for (byte b : new String(Character.toChars(cp)).getBytes(StandardCharsets.UTF_8)) {
                    sb.append('%').append(String.format(Locale.ROOT, "%02X", b & 0xff));
                }
            }
        });
        return sb.toString();
    }

    private boolean hasPathParameter(IrSpec ir) {
        return ir.api().requestParameters().stream().anyMatch(p -> "path".equals(p.in()));
    }

    private void appendEncodeHelper(StringBuilder sb, boolean keyInQuery, boolean hasPathParameter, boolean needsSeparator) {
        String indent2 = INDENT.repeat(2);
        sb.append(INDENT).append("private static String encode(String value) {\n");
        sb.append(indent2).append("return URLEncoder.encode(value, StandardCharsets.UTF_8);\n");
        sb.append(INDENT).append("}\n");
        if (keyInQuery) {
            // 포털은 같은 키를 Encoding 키와 Decoding 키 두 가지로 준다. Encoding 키를 다시 인코딩하면 등록되지 않은 키(30)가 된다.
            // 포털의 키는 Base64 글자(영문자·숫자·+/=)라 퍼센트 인코딩 꼴이 들어 있으면 이미 인코딩한 키다
            sb.append("\n");
            sb.append(INDENT)
              .append("/** 인증키용. 공공데이터포털의 Encoding 키처럼 이미 인코딩한 키(%2B 같은 글자가 든 키)는 다시 인코딩하지 않는다 */\n");
            sb.append(INDENT).append("private static String encodeKey(String value) {\n");
            sb.append(indent2).append("return value.matches(\".*%[0-9A-Fa-f]{2}.*\") ? value : encode(value);\n");
            sb.append(INDENT).append("}\n");
        }
        if (hasPathParameter) {
            sb.append("\n");
            sb.append(INDENT)
              .append("/** 경로 세그먼트용. URLEncoder는 공백을 +로 바꾸지만 경로에서 +는 글자 그대로다 */\n");
            sb.append(INDENT).append("private static String encodePath(String value) {\n");
            sb.append(indent2).append("return encode(value).replace(\"+\", \"%20\");\n");
            sb.append(INDENT).append("}\n");
        }
        if (needsSeparator) {
            sb.append("\n");
            sb.append(INDENT).append("/** 질의에 처음 붙는 파라미터면 ?, 아니면 & */\n");
            sb.append(INDENT).append("private static String separator(StringBuilder url) {\n");
            sb.append(indent2).append("return url.indexOf(\"?\") < 0 ? \"?\" : \"&\";\n");
            sb.append(INDENT).append("}\n");
        }
    }

    /** 생성 메서드가 질의에 붙이는 것(포맷 파라미터와 경로 변수가 아닌 인자)이 있는지 */
    private static boolean hasQueryItems(IrSpec ir, List<RequestParameter> params) {
        return formatParamName(ir) != null || params.stream().anyMatch(p -> !"path".equals(p.in()));
    }

    private String javaType(String type) {
        return TYPE_MAP.getOrDefault(type, DEFAULT_TYPE);
    }
}
