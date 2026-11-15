package io.github.kanabig.curtaincall.event.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.time.OffsetDateTime;
import java.util.List;

public final class SessionRequests {

    private SessionRequests() {}

    public record PriceRequest(
            @NotBlank @Size(max = 10) String grade, @Min(1) int price) {}

    public record CreateRequest(
            @NotNull OffsetDateTime startAt,
            @NotNull OffsetDateTime saleOpenAt,
            @NotEmpty List<@Valid PriceRequest> prices,
            @Min(1) int admissionPerSecond,
            @Min(1) int maxActiveUsers) {}

    /** 보낸 필드만 바꾼다. */
    public record UpdateRequest(
            OffsetDateTime startAt,
            OffsetDateTime saleOpenAt,
            List<@Valid PriceRequest> prices,
            @Min(1) Integer admissionPerSecond,
            @Min(1) Integer maxActiveUsers) {}

    public record RegisterPerformanceRequest(@NotBlank String kopisId) {}
}
