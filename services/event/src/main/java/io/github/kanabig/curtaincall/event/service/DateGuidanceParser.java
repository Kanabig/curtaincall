package io.github.kanabig.curtaincall.event.service;

import io.github.kanabig.curtaincall.event.dto.SessionRule;
import java.time.DayOfWeek;
import java.time.LocalTime;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.springframework.stereotype.Component;

/**
 * EVT-8. KOPIS 공연시간 안내(dtguidance, 자유 텍스트)에서 요일별 시작 시각을 읽는다. "수요일 ~ 금요일(19:00), 토요일(14:00,18:00), HOL(14:00)"처럼 요일(범위 가능)과 괄호 안 시각으로
 * 이루어진 글을 읽는다. 요일만 있는 항목("일요일, 공휴일(15:00)")은 뒤따르는 시각을 같이 쓴다. 읽지 못하는 항목이 하나라도 있거나 비어 있으면 기본 규칙으로 대신한다.
 */
@Component
public class DateGuidanceParser {

    private static final Map<String, DayOfWeek> DAYS = Map.of(
            "월", DayOfWeek.MONDAY,
            "화", DayOfWeek.TUESDAY,
            "수", DayOfWeek.WEDNESDAY,
            "목", DayOfWeek.THURSDAY,
            "금", DayOfWeek.FRIDAY,
            "토", DayOfWeek.SATURDAY,
            "일", DayOfWeek.SUNDAY);
    private static final Pattern GROUP = Pattern.compile("([^()]+?)\\s*(?:\\(([^()]*)\\))?\\s*(?:,|$)");
    private static final Pattern TIME = Pattern.compile("^([01]?\\d|2[0-3]):([0-5]\\d)$");
    private static final Pattern DAY = Pattern.compile("^([월화수목금토일])(?:요일)?$");

    public SessionRule parse(String guidance) {
        if (guidance == null || guidance.isBlank()) {
            return SessionRule.defaults();
        }
        List<SessionRule.Slot> slots = new ArrayList<>();
        Set<LocalTime> holidayTimes = new LinkedHashSet<>();
        List<String> pendingDays = new ArrayList<>();
        Matcher m = GROUP.matcher(guidance.strip());
        int end = 0;
        while (m.find()) {
            if (m.start() != end) {
                return SessionRule.defaults();
            }
            end = m.end();
            String days = m.group(1).strip();
            String times = m.group(2);
            pendingDays.add(days);
            if (times == null) {
                continue;
            }
            List<LocalTime> parsedTimes = parseTimes(times);
            if (parsedTimes.isEmpty() || !apply(pendingDays, parsedTimes, slots, holidayTimes)) {
                return SessionRule.defaults();
            }
            pendingDays.clear();
        }
        if (end != guidance.strip().length() || !pendingDays.isEmpty() || slots.isEmpty()) {
            return SessionRule.defaults();
        }
        return new SessionRule(List.copyOf(slots), List.copyOf(holidayTimes), true);
    }

    private static boolean apply(
            List<String> dayTexts, List<LocalTime> times, List<SessionRule.Slot> slots, Set<LocalTime> holidayTimes) {
        for (String text : dayTexts) {
            if (text.equals("HOL") || text.equals("공휴일")) {
                holidayTimes.addAll(times);
                continue;
            }
            List<DayOfWeek> days = parseDays(text);
            if (days.isEmpty()) {
                return false;
            }
            for (DayOfWeek day : days) {
                for (LocalTime time : times) {
                    SessionRule.Slot slot = new SessionRule.Slot(day, time);
                    if (!slots.contains(slot)) {
                        slots.add(slot);
                    }
                }
            }
        }
        return true;
    }

    /** "수요일", "수", "수요일 ~ 금요일", "금~월"(요일 순서를 한 바퀴 돈다), "평일", "주말". */
    private static List<DayOfWeek> parseDays(String text) {
        String t = text.replaceAll("\\s+", "");
        if (t.equals("평일")) {
            return List.of(
                    DayOfWeek.MONDAY, DayOfWeek.TUESDAY, DayOfWeek.WEDNESDAY, DayOfWeek.THURSDAY, DayOfWeek.FRIDAY);
        }
        if (t.equals("주말")) {
            return List.of(DayOfWeek.SATURDAY, DayOfWeek.SUNDAY);
        }
        String[] range = t.split("~", -1);
        if (range.length == 1) {
            DayOfWeek day = day(range[0]);
            return day == null ? List.of() : List.of(day);
        }
        if (range.length != 2) {
            return List.of();
        }
        DayOfWeek from = day(range[0]);
        DayOfWeek to = day(range[1]);
        if (from == null || to == null) {
            return List.of();
        }
        List<DayOfWeek> days = new ArrayList<>();
        DayOfWeek current = from;
        days.add(current);
        while (current != to) {
            current = current.plus(1);
            days.add(current);
        }
        return days;
    }

    private static DayOfWeek day(String text) {
        Matcher m = DAY.matcher(text);
        return m.matches() ? DAYS.get(m.group(1)) : null;
    }

    private static List<LocalTime> parseTimes(String text) {
        List<LocalTime> times = new ArrayList<>();
        for (String part : text.split(",", -1)) {
            Matcher m = TIME.matcher(part.strip());
            if (!m.matches()) {
                return List.of();
            }
            try {
                times.add(LocalTime.of(Integer.parseInt(m.group(1)), Integer.parseInt(m.group(2))));
            } catch (DateTimeParseException e) {
                return List.of();
            }
        }
        return times;
    }
}
