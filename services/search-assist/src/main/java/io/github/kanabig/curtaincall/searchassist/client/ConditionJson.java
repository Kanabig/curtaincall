package io.github.kanabig.curtaincall.searchassist.client;

import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/** 모델이 돌려준 텍스트에서 JSON 객체를 꺼내 {@link RawConditions}로 바꾼다. 실패는 모두 {@link ExtractionException}. */
final class ConditionJson {

    private ConditionJson() {}

    /**
     * 코드 펜스나 앞뒤 설명이 붙어 있어도 첫 JSON 객체 하나만 읽는다. 문자열 안의 중괄호는 세지 않는다. 닫히지 않으면(출력이 잘림) 실패.
     * 한계: 앞 설명에 '{'가 섞여 있으면 그 지점부터 읽다 실패하고, 호출하는 쪽에서 FALLBACK이 된다.
     */
    static String firstObject(String text) {
        int start = text.indexOf('{');
        if (start < 0) {
            throw ExtractionException.badResponse("llm returned no json object", null);
        }
        int depth = 0;
        boolean inString = false;
        boolean escaped = false;
        for (int i = start; i < text.length(); i++) {
            char c = text.charAt(i);
            if (inString) {
                if (escaped) {
                    escaped = false;
                } else if (c == '\\') {
                    escaped = true;
                } else if (c == '"') {
                    inString = false;
                }
            } else if (c == '"') {
                inString = true;
            } else if (c == '{') {
                depth++;
            } else if (c == '}' && --depth == 0) {
                return text.substring(start, i + 1);
            }
        }
        throw ExtractionException.badResponse("llm returned unterminated json", null);
    }

    static RawConditions parse(ObjectMapper objectMapper, String json) {
        try {
            JsonNode node = objectMapper.readTree(json);
            if (node == null || !node.isObject()) {
                throw ExtractionException.badResponse("llm returned non-object", null);
            }
            return new RawConditions(
                    text(node, "region"),
                    text(node, "dateFrom"),
                    text(node, "dateTo"),
                    text(node, "genre"),
                    integer(node, "maxPrice"),
                    text(node, "keyword"));
        } catch (JacksonException e) {
            throw ExtractionException.badResponse("llm returned malformed json", e);
        }
    }

    private static String text(JsonNode node, String field) {
        JsonNode value = node.get(field);
        if (value == null || value.isNull()) {
            return null;
        }
        if (!value.isString()) {
            throw ExtractionException.badResponse("llm field has wrong type: " + field, null);
        }
        return value.asString();
    }

    private static Long integer(JsonNode node, String field) {
        JsonNode value = node.get(field);
        if (value == null || value.isNull()) {
            return null;
        }
        if (!value.isIntegralNumber() || !value.canConvertToLong()) {
            throw ExtractionException.badResponse("llm field has wrong type: " + field, null);
        }
        return value.asLong();
    }
}
