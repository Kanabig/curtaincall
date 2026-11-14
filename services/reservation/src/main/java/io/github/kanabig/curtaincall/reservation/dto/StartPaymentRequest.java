package io.github.kanabig.curtaincall.reservation.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;

public record StartPaymentRequest(
        @Pattern(regexp = "CARD") String method, @NotBlank String cardToken) {}
