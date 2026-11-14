package io.github.kanabig.curtaincall.reservation.dto;

import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import java.util.List;

public record HoldRequest(@NotEmpty List<@NotNull @Positive Long> seatIds) {}
