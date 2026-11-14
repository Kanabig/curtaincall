package io.github.kanabig.curtaincall.event.service;

import io.github.kanabig.curtaincall.event.dto.PriceTable;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.springframework.stereotype.Component;

/**
 * EVT-7. KOPIS 가격 안내(pcseguidance, 자유 텍스트)에서 등급별 가격을 읽는다. "전석 10,000원", "R석 99,000원, S석 77,000원"처럼 좌석 등급이 붙은 항목만 인정한다. "현장판매
 * 14,900원, 사전예약 9,900원"처럼 구매 방법이나 관람객 구분이 붙은 가격, 비어 있거나 가격이 없는 글, 항목 하나라도 읽지 못하는 글은 기본 등급표로 대신한다. 일부만 읽어 가격표가
 * 틀어지는 것보다 기본 등급표가 낫다.
 */
@Component
public class PriceGuidanceParser {

    private static final Pattern ITEM = Pattern.compile("^(.+?)[\\s:]*([0-9]{1,3}(?:,[0-9]{3})+|[0-9]+)\\s*원$");
    private static final Pattern GRADE_LABEL = Pattern.compile("^(?:[0-9]+층석?|[A-Za-z]{1,4}석?|[가-힣A-Za-z0-9]*석)$");
    private static final Pattern SEPARATOR = Pattern.compile(",(?!\\d{3}(?!\\d))|[/·\\n]|\\s{2,}");
    private static final Pattern PARENTHESIS = Pattern.compile("\\([^)]*\\)");
    private static final int MIN_PRICE = 1_000;
    private static final int MAX_TIERS = 8;

    public PriceTable parse(String guidance) {
        if (guidance == null || guidance.isBlank()) {
            return PriceTable.defaults();
        }
        List<PriceTable.Tier> tiers = new ArrayList<>();
        Set<String> grades = new HashSet<>();
        for (String raw : SEPARATOR.split(PARENTHESIS.matcher(guidance).replaceAll(" "))) {
            String item = raw.strip();
            if (item.isEmpty()) {
                continue;
            }
            Matcher m = ITEM.matcher(item);
            if (!m.matches()) {
                return PriceTable.defaults();
            }
            String grade = m.group(1).strip();
            int price = Integer.parseInt(m.group(2).replace(",", ""));
            if (!GRADE_LABEL.matcher(grade).matches()
                    || price < MIN_PRICE
                    || !grades.add(grade)
                    || tiers.size() >= MAX_TIERS) {
                return PriceTable.defaults();
            }
            tiers.add(new PriceTable.Tier(grade, price));
        }
        if (tiers.isEmpty()) {
            return PriceTable.defaults();
        }
        tiers.sort(Comparator.comparingInt(PriceTable.Tier::price).reversed());
        return new PriceTable(List.copyOf(tiers), true);
    }
}
