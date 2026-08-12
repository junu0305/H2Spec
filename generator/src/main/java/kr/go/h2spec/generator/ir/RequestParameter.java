package kr.go.h2spec.generator.ir;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

@JsonIgnoreProperties(ignoreUnknown = true)
public record RequestParameter(
        String name,
        String in,
        String type,
        boolean required,
        String description,
        @JsonProperty("default") String defaultValue,
        String example,
        String pattern) {
}
