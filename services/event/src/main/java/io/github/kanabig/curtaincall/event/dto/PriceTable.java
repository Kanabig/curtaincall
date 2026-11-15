package io.github.kanabig.curtaincall.event.dto;

import java.util.List;

/**
 * 등급별 가격표. tiers는 비싼 등급부터다. parsed가 false면 기본 등급표다.
 */
public record PriceTable(List<Tier> tiers, boolean parsed) {

    public record Tier(String grade, int price) {}

    /** 파싱에 실패했을 때 쓰는 시연용 등급표. */
    public static PriceTable defaults() {
        return new PriceTable(
                List.of(new Tier("VIP", 150_000), new Tier("R", 120_000), new Tier("S", 90_000), new Tier("A", 60_000)),
                false);
    }

    /** depth(0~999)에 해당하는 등급. 무대에 가까울수록 앞(비싼) 등급이다. */
    public Tier tierAt(int depth) {
        int index = Math.min(tiers.size() - 1, depth * tiers.size() / 1000);
        return tiers.get(index);
    }
}
