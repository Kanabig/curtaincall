package io.github.kanabig.curtaincall.event.client;

import io.github.kanabig.curtaincall.event.dto.KopisFacility;
import io.github.kanabig.curtaincall.event.dto.KopisPerformanceDetail;
import io.github.kanabig.curtaincall.event.dto.KopisPerformanceSummary;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.List;
import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.parsers.ParserConfigurationException;
import org.springframework.stereotype.Component;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;
import org.xml.sax.SAXException;

/**
 * KOPIS 응답 XML 파서. 외부 응답이라 DTD와 외부 엔티티를 막는다. 식별자와 기간처럼 없으면 저장할 수 없는 항목이 비었을 때만 {@link
 * KopisParseException}을 던지고, 나머지 비어 있거나 형식이 다른 값은 null로 둔다.
 */
@Component
public class KopisXmlParser {

    private static final DateTimeFormatter DATE = DateTimeFormatter.ofPattern("yyyy.MM.dd");
    private static final DateTimeFormatter DATE_TIME = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss[.SSSSSS]");

    /** 목록 항목 하나가 깨져도 나머지는 살린다. 건너뛴 항목 수는 skipped로 알려 호출한 쪽이 이번 조회를 완전하다고 보지 않게 한다. */
    public ListResult parseList(String xml) {
        List<KopisPerformanceSummary> items = new ArrayList<>();
        int skipped = 0;
        for (Element db : dbs(xml)) {
            try {
                items.add(new KopisPerformanceSummary(
                        required(db, "mt20id"),
                        required(db, "prfnm"),
                        requiredDate(db, "prfpdfrom"),
                        requiredDate(db, "prfpdto"),
                        text(db, "fcltynm"),
                        text(db, "poster"),
                        text(db, "area"),
                        text(db, "genrenm"),
                        "Y".equals(text(db, "openrun")),
                        text(db, "prfstate")));
            } catch (KopisParseException e) {
                skipped++;
            }
        }
        return new ListResult(items, skipped);
    }

    public record ListResult(List<KopisPerformanceSummary> items, int skipped) {}

    public KopisPerformanceDetail parseDetail(String xml) {
        List<Element> dbs = dbs(xml);
        if (dbs.isEmpty()) {
            throw new KopisParseException("공연상세 응답에 db가 없다");
        }
        Element db = dbs.get(0);
        return new KopisPerformanceDetail(
                required(db, "mt20id"),
                required(db, "prfnm"),
                text(db, "mt10id"),
                text(db, "mt13id"),
                text(db, "fcltynm"),
                requiredDate(db, "prfpdfrom"),
                requiredDate(db, "prfpdto"),
                text(db, "genrenm"),
                text(db, "area"),
                text(db, "prfstate"),
                "Y".equals(text(db, "openrun")),
                text(db, "poster"),
                text(db, "prfruntime"),
                text(db, "prfage"),
                text(db, "pcseguidance"),
                text(db, "dtguidance"),
                dateTime(text(db, "updatedate")),
                relates(db));
    }

    public KopisFacility parseFacility(String xml) {
        List<Element> dbs = dbs(xml);
        if (dbs.isEmpty()) {
            throw new KopisParseException("공연시설상세 응답에 db가 없다");
        }
        Element db = dbs.get(0);
        List<KopisFacility.Hall> halls = new ArrayList<>();
        Element mt13s = child(db, "mt13s");
        if (mt13s != null) {
            for (Element mt13 : children(mt13s, "mt13")) {
                String id = text(mt13, "mt13id");
                if (id == null) {
                    continue;
                }
                halls.add(new KopisFacility.Hall(
                        id,
                        orEmpty(text(mt13, "prfplcnm")),
                        number(text(mt13, "seatscale")),
                        number(text(mt13, "disabledseatscale"))));
            }
        }
        return new KopisFacility(
                required(db, "mt10id"),
                required(db, "fcltynm"),
                number(text(db, "mt13cnt")),
                number(text(db, "seatscale")),
                text(db, "adres"),
                decimal(text(db, "la")),
                decimal(text(db, "lo")),
                halls);
    }

    private List<KopisPerformanceDetail.Relate> relates(Element db) {
        List<KopisPerformanceDetail.Relate> result = new ArrayList<>();
        Element relates = child(db, "relates");
        if (relates == null) {
            return result;
        }
        for (Element relate : children(relates, "relate")) {
            String url = text(relate, "relateurl");
            if (url != null) {
                result.add(new KopisPerformanceDetail.Relate(text(relate, "relatenm"), url));
            }
        }
        return result;
    }

    private List<Element> dbs(String xml) {
        Document document = parse(xml);
        Element root = document.getDocumentElement();
        if (root == null || !"dbs".equals(root.getTagName())) {
            // 인증 오류 등은 <dbs> 대신 <returncode>를 담은 다른 루트로 온다
            throw new KopisParseException("예상하지 못한 루트 요소: " + (root == null ? "없음" : root.getTagName()));
        }
        return children(root, "db");
    }

    private Document parse(String xml) {
        if (xml == null || xml.isBlank()) {
            throw new KopisParseException("빈 응답");
        }
        try {
            DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
            factory.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true);
            factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
            factory.setXIncludeAware(false);
            factory.setExpandEntityReferences(false);
            DocumentBuilder builder = factory.newDocumentBuilder();
            return builder.parse(new ByteArrayInputStream(xml.getBytes(StandardCharsets.UTF_8)));
        } catch (ParserConfigurationException | SAXException | IOException e) {
            throw new KopisParseException("XML 파싱 실패", e);
        }
    }

    private static List<Element> children(Element parent, String name) {
        List<Element> result = new ArrayList<>();
        NodeList nodes = parent.getChildNodes();
        for (int i = 0; i < nodes.getLength(); i++) {
            Node node = nodes.item(i);
            if (node instanceof Element element && name.equals(element.getTagName())) {
                result.add(element);
            }
        }
        return result;
    }

    private static Element child(Element parent, String name) {
        List<Element> found = children(parent, name);
        return found.isEmpty() ? null : found.get(0);
    }

    /** 공백만 있는 값(KOPIS는 빈 항목에 공백 하나를 넣는다)은 null로 본다. */
    private static String text(Element parent, String name) {
        Element element = child(parent, name);
        if (element == null) {
            return null;
        }
        String value = element.getTextContent().trim();
        return value.isEmpty() ? null : value;
    }

    private static String required(Element parent, String name) {
        String value = text(parent, name);
        if (value == null) {
            throw new KopisParseException("필수 항목이 없다: " + name);
        }
        return value;
    }

    private static LocalDate requiredDate(Element parent, String name) {
        String value = required(parent, name);
        try {
            return LocalDate.parse(value, DATE);
        } catch (DateTimeParseException e) {
            throw new KopisParseException("날짜 형식 오류: " + name + "=" + value);
        }
    }

    private static LocalDateTime dateTime(String value) {
        if (value == null) {
            return null;
        }
        try {
            return LocalDateTime.parse(value, DATE_TIME);
        } catch (DateTimeParseException e) {
            return null;
        }
    }

    private static Integer number(String value) {
        if (value == null) {
            return null;
        }
        try {
            return Integer.valueOf(value.replace(",", ""));
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private static BigDecimal decimal(String value) {
        if (value == null) {
            return null;
        }
        try {
            return new BigDecimal(value);
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private static String orEmpty(String value) {
        return value == null ? "" : value;
    }
}
