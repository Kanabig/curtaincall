package io.github.kanabig.curtaincall.reservation.dto;

import jakarta.validation.constraints.NotBlank;

public record CreateOrderRequest(@NotBlank String holdId) {}
