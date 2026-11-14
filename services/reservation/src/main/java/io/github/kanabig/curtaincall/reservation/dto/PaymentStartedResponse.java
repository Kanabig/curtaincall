package io.github.kanabig.curtaincall.reservation.dto;

import io.github.kanabig.curtaincall.reservation.entity.OrderStatus;

public record PaymentStartedResponse(OrderStatus status) {}
