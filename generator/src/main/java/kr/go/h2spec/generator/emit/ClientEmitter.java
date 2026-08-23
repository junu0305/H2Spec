package kr.go.h2spec.generator.emit;

import kr.go.h2spec.generator.ir.IrSpec;
import kr.go.h2spec.generator.ir.RequestParameter;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/** IR을 RestTemplate 기반 API 클라이언트 단일 .java 소스로 방출한다. */
public class ClientEmitter {

    private static final String INDENT = "    ";
    private static final String SERVICE_KEY_PARAM = "serviceKey";
    private static final Map<String, String> TYPE_MAP = Map.of(
            "string", "String",
            "integer", "Long",
            "number", "Double",
            "boolean", "Boolean");
    private static final String DEFAULT_TYPE = "String";

    public String emit(IrSpec ir) {
        String className = JavaNames.pascal(ir.api().apiId()) + "Client";
        String responseClassName = JavaNames.pascal(ir.api().apiId()) + "Response";
        boolean isXml = "XML".equals(ir.api().responseFormat());
        RequestParameter serviceKeyParam = findServiceKeyParam(ir);
        List<RequestParameter> params = paramsExcludingServiceKey(ir);

        StringBuilder sb = new StringBuilder();
        sb.append("package ").append(ir.generatorHints().targetPackage()).append(";\n\n");
        appendImports(sb, ir, responseClassName, isXml);
        appendClassJavadoc(sb, ir);
        sb.append("public class ").append(className).append(" {\n\n");
        appendFields(sb, ir, isXml);
        sb.append("\n");
        appendConstructors(sb, className, serviceKeyParam);
        sb.append("\n");
        appendDefaultRestTemplateFactory(sb);
        sb.append("\n");
        appendApiMethod(sb, ir, params, responseClassName, isXml);
        sb.append("\n");
        appendEncodeHelper(sb);
        sb.append("}\n");
        return sb.toString();
    }

    private RequestParameter findServiceKeyParam(IrSpec ir) {
        for (RequestParameter p : ir.api().requestParameters()) {
            if (SERVICE_KEY_PARAM.equals(p.name())) {
                return p;
            }
        }
        throw new IllegalArgumentException("requestParameters에 serviceKey 파라미터가 없습니다.");
    }

    private List<RequestParameter> paramsExcludingServiceKey(IrSpec ir) {
        List<RequestParameter> result = new ArrayList<>();
        for (RequestParameter p : ir.api().requestParameters()) {
            if (!SERVICE_KEY_PARAM.equals(p.name())) {
                result.add(p);
            }
        }
        return result;
    }

    // java.* 그룹 → jackson/spring 그룹(XmlMapper 또는 ObjectMapper 선택) → 프로젝트 내부 그룹. 그룹마다 빈 줄.
    private void appendImports(StringBuilder sb, IrSpec ir, String responseClassName, boolean isXml) {
        sb.append("import java.io.IOException;\n");
        sb.append("import java.net.URI;\n");
        sb.append("import java.net.URLEncoder;\n");
        sb.append("import java.nio.charset.StandardCharsets;\n");
        sb.append("\n");

        if (isXml) {
            sb.append("import com.fasterxml.jackson.dataformat.xml.XmlMapper;\n");
        } else {
            sb.append("import com.fasterxml.jackson.databind.ObjectMapper;\n");
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

    private void appendClassJavadoc(StringBuilder sb, IrSpec ir) {
        sb.append("/**\n");
        sb.append(" * ").append(ir.api().apiName()).append(" 클라이언트.\n");
        sb.append(" * H2Spec generator가 자동 생성한 코드입니다. 직접 수정하지 마세요.\n");
        sb.append(" */\n");
    }

    private void appendFields(StringBuilder sb, IrSpec ir, boolean isXml) {
        sb.append(INDENT).append("private static final String BASE_URL = \"")
          .append(ir.api().baseUrl()).append("\";\n");
        sb.append(INDENT).append("private static final String SUCCESS_CODE = \"")
          .append(ir.api().errorSpec().successResultCode()).append("\";\n");
        sb.append("\n");
        sb.append(INDENT).append("private final RestTemplate restTemplate;\n");
        if (isXml) {
            sb.append(INDENT).append("private final XmlMapper xmlMapper = new XmlMapper();\n");
        } else {
            sb.append(INDENT).append("private final ObjectMapper objectMapper = new ObjectMapper();\n");
        }
        sb.append(INDENT).append("private final String serviceKey;\n");
    }

    private void appendConstructors(StringBuilder sb, String className, RequestParameter serviceKeyParam) {
        String indent2 = INDENT.repeat(2);
        sb.append(INDENT).append("/**\n");
        sb.append(INDENT).append(" * @param serviceKey ").append(serviceKeyParam.description()).append("\n");
        sb.append(INDENT).append(" */\n");
        sb.append(INDENT).append("public ").append(className).append("(String serviceKey) {\n");
        sb.append(indent2).append("this(serviceKey, defaultRestTemplate());\n");
        sb.append(INDENT).append("}\n");
        sb.append("\n");
        sb.append(INDENT).append("public ").append(className).append("(String serviceKey, RestTemplate restTemplate) {\n");
        sb.append(indent2).append("this.serviceKey = serviceKey;\n");
        sb.append(indent2).append("this.restTemplate = restTemplate;\n");
        sb.append(INDENT).append("}\n");
    }

    private void appendDefaultRestTemplateFactory(StringBuilder sb) {
        String indent2 = INDENT.repeat(2);
        String indent4 = INDENT.repeat(4);
        sb.append(INDENT).append("private static RestTemplate defaultRestTemplate() {\n");
        sb.append(indent2).append("RestTemplate restTemplate = new RestTemplate(\n");
        sb.append(indent4).append("new BufferingClientHttpRequestFactory(new SimpleClientHttpRequestFactory()));\n");
        sb.append(indent2).append("restTemplate.getInterceptors().add(new PublicDataErrorInterceptor(SUCCESS_CODE));\n");
        sb.append(indent2).append("return restTemplate;\n");
        sb.append(INDENT).append("}\n");
    }

    private void appendApiMethod(StringBuilder sb, IrSpec ir, List<RequestParameter> params,
                                  String responseClassName, boolean isXml) {
        String indent2 = INDENT.repeat(2);
        String indent3 = INDENT.repeat(3);
        String methodName = JavaNames.camel(ir.api().endpoint().substring(1));

        sb.append(INDENT).append("/**\n");
        sb.append(INDENT).append(" * ").append(ir.api().description()).append("\n");
        sb.append(INDENT).append(" *\n");
        for (RequestParameter p : params) {
            String suffix = p.required() ? "" : " (null이면 생략)";
            sb.append(INDENT).append(" * @param ").append(JavaNames.camel(p.name())).append(" ")
              .append(p.description()).append(suffix).append("\n");
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
          .append(ir.api().endpoint()).append("\");\n");
        sb.append(indent2).append("url.append(\"?").append(SERVICE_KEY_PARAM).append("=\").append(encode(")
          .append(SERVICE_KEY_PARAM).append("));\n");
        for (RequestParameter p : params) {
            String varName = JavaNames.camel(p.name());
            String valueExpr = "string".equals(p.type()) ? "encode(" + varName + ")" : varName;
            String appendLine = "url.append(\"&" + p.name() + "=\").append(" + valueExpr + ");";
            if (p.required()) {
                sb.append(indent2).append(appendLine).append("\n");
            } else {
                sb.append(indent2).append("if (").append(varName).append(" != null) {\n");
                sb.append(indent3).append(appendLine).append("\n");
                sb.append(indent2).append("}\n");
            }
        }
        sb.append(indent2).append("URI uri = URI.create(url.toString());\n");
        sb.append(indent2).append("byte[] body = restTemplate.getForObject(uri, byte[].class);\n");
        sb.append(indent2).append("try {\n");
        String mapperVar = isXml ? "xmlMapper" : "objectMapper";
        sb.append(indent3).append("return ").append(mapperVar).append(".readValue(body, ")
          .append(responseClassName).append(".class);\n");
        sb.append(indent2).append("} catch (IOException e) {\n");
        // 파싱 실패 예외에도 인증키가 남지 않도록 URI를 마스킹해서 넣는다
        sb.append(indent3).append(
                "throw new IllegalStateException(\"응답 파싱 실패: \" + ServiceKeyMasker.mask(uri.toString()), e);\n");
        sb.append(indent2).append("}\n");
        sb.append(INDENT).append("}\n");
    }

    private void appendEncodeHelper(StringBuilder sb) {
        String indent2 = INDENT.repeat(2);
        sb.append(INDENT).append("private static String encode(String value) {\n");
        sb.append(indent2).append("return URLEncoder.encode(value, StandardCharsets.UTF_8);\n");
        sb.append(INDENT).append("}\n");
    }

    private String javaType(String type) {
        return TYPE_MAP.getOrDefault(type, DEFAULT_TYPE);
    }
}
