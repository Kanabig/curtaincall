package io.github.kanabig.curtaincall.searchassist.service;

import io.github.kanabig.curtaincall.searchassist.client.RawConditions;
import io.github.kanabig.curtaincall.searchassist.domain.KopisCodes;
import io.github.kanabig.curtaincall.searchassist.dto.SearchConditions;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.time.temporal.ChronoUnit;
import org.springframework.stereotype.Component;

/** 추출 경로와 상관없이 모든 결과가 통과하는 검증. 필드 하나라도 어긋나면 {@link InvalidConditionsException}. */
@Component
public class ConditionValidator {

    static final int MAX_KEYWORD_LENGTH = 50;
    static final int MIN_PRICE = 1_000;
    static final int MAX_PRICE = 1_000_000;
    static final int MAX_DAYS = 365;

    public SearchConditions validate(RawConditions raw, LocalDate today) {
        String region = enumValue(raw.region(), KopisCodes.REGIONS, "region");
        String genre = enumValue(raw.genre(), KopisCodes.GENRES, "genre");
        LocalDate from = date(raw.dateFrom(), "dateFrom");
        LocalDate to = date(raw.dateTo(), "dateTo");
        checkRange(from, to, today);
        Integer maxPrice = price(raw.maxPrice());
        String keyword = keyword(raw.keyword());
        return new SearchConditions(region, from, to, genre, maxPrice, keyword);
    }

    /** 제어문자를 지우고 앞뒤 공백을 자른다. 길이 제한은 지킨 채로 돌려준다(fallback에서도 쓴다). */
    static String cleanKeyword(String value, int maxLength) {
        if (value == null) {
            return null;
        }
        StringBuilder sb = new StringBuilder(value.length());
        value.codePoints().filter(cp -> !Character.isISOControl(cp)).forEach(sb::appendCodePoint);
        String cleaned = sb.toString().strip();
        if (cleaned.isEmpty()) {
            return null;
        }
        return cleaned.codePointCount(0, cleaned.length()) > maxLength
                ? cleaned.substring(0, cleaned.offsetByCodePoints(0, maxLength)).strip()
                : cleaned;
    }

    private static String enumValue(String value, Iterable<String> allowed, String field) {
        if (value == null || value.isBlank()) {
            return null;
        }
        for (String candidate : allowed) {
            if (candidate.equals(value)) {
                return candidate;
            }
        }
        throw new InvalidConditionsException(field);
    }

    private static LocalDate date(String value, String field) {
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            return LocalDate.parse(value.strip());
        } catch (DateTimeParseException e) {
            throw new InvalidConditionsException(field);
        }
    }

    private static void checkRange(LocalDate from, LocalDate to, LocalDate today) {
        LocalDate last = today.plusDays(MAX_DAYS);
        if (from != null && (from.isBefore(today) || from.isAfter(last))) {
            throw new InvalidConditionsException("dateFrom");
        }
        if (to != null && (to.isBefore(today) || to.isAfter(last))) {
            throw new InvalidConditionsException("dateTo");
        }
        if (from != null && to != null && (from.isAfter(to) || ChronoUnit.DAYS.between(from, to) > MAX_DAYS)) {
            throw new InvalidConditionsException("dateTo");
        }
    }

    private static Integer price(Long value) {
        if (value == null) {
            return null;
        }
        if (value < MIN_PRICE || value > MAX_PRICE) {
            throw new InvalidConditionsException("maxPrice");
        }
        return value.intValue();
    }

    private static String keyword(String value) {
        if (value == null) {
            return null;
        }
        String cleaned = cleanKeyword(value, Integer.MAX_VALUE);
        if (cleaned != null && cleaned.codePointCount(0, cleaned.length()) > MAX_KEYWORD_LENGTH) {
            throw new InvalidConditionsException("keyword");
        }
        return cleaned;
    }
}
