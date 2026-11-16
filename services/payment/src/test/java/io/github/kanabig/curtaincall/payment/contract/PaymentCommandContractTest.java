package io.github.kanabig.curtaincall.payment.contract;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.kanabig.curtaincall.outbox.message.EventEnvelope;
import io.github.kanabig.curtaincall.outbox.message.EventEnvelopes;
import io.github.kanabig.curtaincall.payment.dto.ApprovePaymentCommand;
import io.github.kanabig.curtaincall.payment.dto.CancelPaymentCommand;
import io.github.kanabig.curtaincall.payment.entity.CancelReason;
import io.github.kanabig.curtaincall.testsupport.EventContract;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.json.JsonTest;
import tools.jackson.databind.ObjectMapper;

/** reservation이 발행한 명령 골든을 payment가 읽는 방식(payload → 명령 DTO)으로 읽는다. */
@JsonTest
class PaymentCommandContractTest {

    @Autowired
    ObjectMapper mapper;

    @Test
    @DisplayName("ApprovePayment 골든을 읽는다")
    void approve() throws Exception {
        EventEnvelope envelope =
                new EventEnvelopes(mapper, EventContract.CLOCK).read(EventContract.golden("ApprovePayment"));

        ApprovePaymentCommand command = mapper.treeToValue(envelope.payload(), ApprovePaymentCommand.class);

        assertThat(command).isEqualTo(new ApprovePaymentCommand("ord-1", "42", 132000L, "card-token-1"));
    }

    @Test
    @DisplayName("CancelPayment 골든을 읽는다")
    void cancel() throws Exception {
        EventEnvelope envelope =
                new EventEnvelopes(mapper, EventContract.CLOCK).read(EventContract.golden("CancelPayment"));

        CancelPaymentCommand command = mapper.treeToValue(envelope.payload(), CancelPaymentCommand.class);

        assertThat(command).isEqualTo(new CancelPaymentCommand("ord-1", "pay-1", 132000L, CancelReason.SEAT_LOST));
    }
}
