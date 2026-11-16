package io.github.kanabig.curtaincall.searchassist.client;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.temporal.TemporalAdjusters;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.springframework.stereotype.Component;

/**
 * 규칙 기반 추출기. API 키가 없을 때와 하루 호출 한도를 넘었을 때 쓴다.
 *
 * <p>다루는 것: 시·도 별칭, 장르 별칭, "N만원 이하·미만·대", 날짜 표현(오늘, 내일, 모레, 이번·다음 주말, 다음 주, 다음 달, N월, M월 D일, YYYY-MM-DD). 다루지 못하는 것: 구·동 단위 지역(keyword로
 * 간다), 부정, 복수 조건, 구어 가격. 사전에 걸린 표현을 지운 나머지가 keyword다.
 */
@Component
public class MockConditionExtractor implements ConditionExtractor {

    private static final Map<String, String> REGION_ALIASES = orderedByLength(Map.ofEntries(
            Map.entry("서울특별시", "서울특별시"),
            Map.entry("서울", "서울특별시"),
            Map.entry("부산광역시", "부산광역시"),
            Map.entry("부산", "부산광역시"),
            Map.entry("대구광역시", "대구광역시"),
            Map.entry("대구", "대구광역시"),
            Map.entry("인천광역시", "인천광역시"),
            Map.entry("인천", "인천광역시"),
            Map.entry("광주광역시", "전남광주통합특별시"),
            Map.entry("광주", "전남광주통합특별시"),
            Map.entry("대전광역시", "대전광역시"),
            Map.entry("대전", "대전광역시"),
            Map.entry("울산광역시", "울산광역시"),
            Map.entry("울산", "울산광역시"),
            Map.entry("세종특별자치시", "세종특별자치시"),
            Map.entry("세종", "세종특별자치시"),
            Map.entry("경기도", "경기도"),
            Map.entry("경기", "경기도"),
            Map.entry("강원특별자치도", "강원특별자치도"),
            Map.entry("강원도", "강원특별자치도"),
            Map.entry("강원", "강원특별자치도"),
            Map.entry("충청북도", "충청북도"),
            Map.entry("충북", "충청북도"),
            Map.entry("충청남도", "충청남도"),
            Map.entry("충남", "충청남도"),
            Map.entry("전북특별자치도", "전북특별자치도"),
            Map.entry("전라북도", "전북특별자치도"),
            Map.entry("전북", "전북특별자치도"),
            Map.entry("전라남도", "전남광주통합특별시"),
            Map.entry("전남", "전남광주통합특별시"),
            Map.entry("경상북도", "경상북도"),
            Map.entry("경북", "경상북도"),
            Map.entry("경상남도", "경상남도"),
            Map.entry("경남", "경상남도"),
            Map.entry("제주특별자치도", "제주특별자치도"),
            Map.entry("제주도", "제주특별자치도"),
            Map.entry("제주", "제주특별자치도")));

    private static final Map<String, String> GENRE_ALIASES = orderedByLength(Map.ofEntries(
            Map.entry("뮤지컬", "뮤지컬"),
            Map.entry("연극", "연극"),
            Map.entry("콘서트", "대중음악"),
            Map.entry("대중음악", "대중음악"),
            Map.entry("클래식", "서양음악(클래식)"),
            Map.entry("오케스트라", "서양음악(클래식)"),
            Map.entry("국악", "한국음악(국악)"),
            Map.entry("발레", "무용(서양/한국무용)"),
            Map.entry("무용", "무용(서양/한국무용)"),
            Map.entry("대중무용", "대중무용"),
            Map.entry("서커스", "서커스/마술"),
            Map.entry("마술", "서커스/마술")));

    private static final Set<String> STOPWORDS = Set.of(
            "공연", "공연을", "공연이", "보고", "싶어", "싶다", "싶어요", "찾아줘", "찾아", "알려줘", "추천", "추천해줘", "있어", "있나요", "해줘", "에서",
            "에서는", "에", "의", "를", "을", "은", "는", "이", "가", "도", "좀", "하는", "있는", "볼", "만한", "이하", "이내", "미만", "까지",
            "에는", "으로", "로", "중", "중에");

    private static final Pattern ISO_DATE = Pattern.compile("(\\d{4})-(\\d{2})-(\\d{2})");
    private static final Pattern MONTH_DAY = Pattern.compile("(\\d{1,2})\\s*월\\s*(\\d{1,2})\\s*일");
    private static final Pattern MONTH = Pattern.compile("(\\d{1,2})\\s*월");
    private static final Pattern PRICE = Pattern.compile("(\\d+)\\s*만\\s*원?\\s*(대|이하|이내|미만|까지)?");

    @Override
    public RawConditions extract(String query, LocalDate today) {
        String text = query;
        LocalDate from = null;
        LocalDate to = null;

        Matcher m = ISO_DATE.matcher(text);
        if (m.find()) {
            LocalDate d = parse(m.group(1), m.group(2), m.group(3));
            if (d != null) {
                from = d;
                to = d;
                text = blank(text, m);
            }
        }
        if (from == null) {
            m = MONTH_DAY.matcher(text);
            if (m.find()) {
                LocalDate d = monthDay(today, Integer.parseInt(m.group(1)), Integer.parseInt(m.group(2)));
                if (d != null) {
                    from = d;
                    to = d;
                    text = blank(text, m);
                }
            }
        }

        Long maxPrice = null;
        m = PRICE.matcher(text);
        if (m.find()) {
            long base = Long.parseLong(m.group(1)) * 10_000L;
            String suffix = m.group(2);
            maxPrice = "대".equals(suffix) ? base + 9_999 : "미만".equals(suffix) ? base - 1 : base;
            text = blank(text, m);
        }

        if (from == null) {
            m = MONTH.matcher(text);
            if (m.find()) {
                int month = Integer.parseInt(m.group(1));
                if (month >= 1 && month <= 12) {
                    YearMonth ym = YearMonth.of(today.getYear(), month);
                    if (ym.isBefore(YearMonth.from(today))) {
                        ym = ym.plusYears(1);
                    }
                    from = ym.atDay(1).isBefore(today) ? today : ym.atDay(1);
                    to = ym.atEndOfMonth();
                    text = blank(text, m);
                }
            }
        }
        if (from == null) {
            String[] holder = {text};
            LocalDate[] range = relativeDates(holder, today);
            if (range != null) {
                from = range[0];
                to = range[1];
                text = holder[0];
            }
        }

        String region = null;
        for (Map.Entry<String, String> e : REGION_ALIASES.entrySet()) {
            if (text.contains(e.getKey())) {
                region = e.getValue();
                text = text.replace(e.getKey(), " ");
                break;
            }
        }
        String genre = null;
        for (Map.Entry<String, String> e : GENRE_ALIASES.entrySet()) {
            if (text.contains(e.getKey())) {
                genre = e.getValue();
                text = text.replace(e.getKey(), " ");
                break;
            }
        }

        return new RawConditions(
                region,
                from == null ? null : from.toString(),
                to == null ? null : to.toString(),
                genre,
                maxPrice,
                keyword(text));
    }

    private static LocalDate[] relativeDates(String[] text, LocalDate today) {
        String t = text[0];
        String[][] words = {
            {"이번 주말", "ws"}, {"다음 주말", "nws"}, {"다음 주", "nw"}, {"다음 달", "nm"}, {"내일", "1"}, {"모레", "2"}, {"오늘", "0"}
        };
        for (String[] w : words) {
            if (!t.contains(w[0])) {
                continue;
            }
            text[0] = t.replace(w[0], " ");
            return switch (w[1]) {
                case "ws" -> weekend(today);
                case "nws" -> weekend(today.with(TemporalAdjusters.next(DayOfWeek.MONDAY)));
                case "nw" -> {
                    LocalDate monday = today.with(TemporalAdjusters.next(DayOfWeek.MONDAY));
                    yield new LocalDate[] {monday, monday.plusDays(6)};
                }
                case "nm" -> {
                    YearMonth next = YearMonth.from(today).plusMonths(1);
                    yield new LocalDate[] {next.atDay(1), next.atEndOfMonth()};
                }
                default -> {
                    LocalDate d = today.plusDays(Long.parseLong(w[1]));
                    yield new LocalDate[] {d, d};
                }
            };
        }
        return null;
    }

    private static LocalDate[] weekend(LocalDate base) {
        LocalDate saturday = base.with(TemporalAdjusters.nextOrSame(DayOfWeek.SATURDAY));
        if (base.getDayOfWeek() == DayOfWeek.SUNDAY) {
            return new LocalDate[] {base, base};
        }
        return new LocalDate[] {saturday, saturday.plusDays(1)};
    }

    private static LocalDate monthDay(LocalDate today, int month, int day) {
        try {
            LocalDate d = LocalDate.of(today.getYear(), month, day);
            return d.isBefore(today) ? d.plusYears(1) : d;
        } catch (RuntimeException e) {
            return null;
        }
    }

    private static LocalDate parse(String y, String mo, String d) {
        try {
            return LocalDate.of(Integer.parseInt(y), Integer.parseInt(mo), Integer.parseInt(d));
        } catch (RuntimeException e) {
            return null;
        }
    }

    private static String blank(String text, Matcher m) {
        return text.substring(0, m.start()) + " " + text.substring(m.end());
    }

    private static String keyword(String text) {
        List<String> kept = new ArrayList<>();
        for (String token : text.trim().split("\\s+")) {
            if (!token.isEmpty() && !STOPWORDS.contains(token)) {
                kept.add(token);
            }
        }
        return kept.isEmpty() ? null : String.join(" ", kept);
    }

    private static Map<String, String> orderedByLength(Map<String, String> source) {
        Map<String, String> sorted = new LinkedHashMap<>();
        source.entrySet().stream()
                .sorted(Comparator.comparingInt(
                                (Map.Entry<String, String> e) -> e.getKey().length())
                        .reversed()
                        .thenComparing(Map.Entry::getKey))
                .forEach(e -> sorted.put(e.getKey(), e.getValue()));
        return sorted;
    }
}
