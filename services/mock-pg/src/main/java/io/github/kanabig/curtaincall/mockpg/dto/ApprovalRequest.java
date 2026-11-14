package io.github.kanabig.curtaincall.mockpg.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Positive;

public record ApprovalRequest(
        @NotBlank String merchantOrderId,
        @Positive long amount,
        @NotBlank String cardToken) {}
