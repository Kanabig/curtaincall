package io.github.kanabig.curtaincall.notification.service;

import com.fasterxml.jackson.databind.JsonNode;
import io.github.kanabig.curtaincall.notification.client.UserClient;
import io.github.kanabig.curtaincall.notification.client.UserContact;
import io.github.kanabig.curtaincall.notification.config.NotificationProperties;
import io.github.kanabig.curtaincall.outbox.message.EventEnvelope;
import java.text.NumberFormat;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.stereotype.Service;

/**
 * [NTF-1] reservation.order 이벤트를 메일로 보낸다. 이벤트에는 userId만 있으므로 이메일은 user에서 조회한다.
 *
 * <p>예외는 삼키지 않는다. 호출하는 IdempotentConsumer의 트랜잭션이 롤백되어 processed_event 기록이 사라지고, 컨테이너의 백오프 재시도와 DLT가 이어받는다.
 */
@Service
public class NotificationService {

    private static final Logger log = LoggerFactory.getLogger(NotificationService.class);

    private final UserClient userClient;
    private final JavaMailSender mailSender;
    private final NotificationProperties properties;

    public NotificationService(UserClient userClient, JavaMailSender mailSender, NotificationProperties properties) {
        this.userClient = userClient;
        this.mailSender = mailSender;
        this.properties = properties;
    }

    public void handle(EventEnvelope envelope) {
        JsonNode payload = envelope.payload();
        Template template = switch (envelope.eventType()) {
            case "ReservationConfirmed" -> new Template("[curtaincall] 예매가 확정되었습니다", "예매가 확정되었습니다.");
            case "ReservationCancelled" -> new Template("[curtaincall] 예매가 취소되었습니다", "예매가 취소되고 결제 금액이 환불되었습니다.");
            case "OrderRefunded" -> new Template("[curtaincall] 결제 금액이 환불되었습니다", "선택하신 좌석을 확보하지 못해 결제 금액이 전액 환불되었습니다.");
            default -> null;
        };
        if (template == null || payload == null || !payload.hasNonNull("userId")) {
            log.info("메일 대상이 아닌 이벤트를 건너뛴다: eventId={} type={}", envelope.eventId(), envelope.eventType());
            return;
        }
        UserContact contact = userClient.fetch(payload.get("userId").asLong());

        SimpleMailMessage message = new SimpleMailMessage();
        message.setFrom(properties.from());
        message.setTo(contact.email());
        message.setSubject(template.subject());
        message.setText(body(template.lead(), payload));
        mailSender.send(message);
        log.info("메일을 보냈다: eventId={} type={}", envelope.eventId(), envelope.eventType());
    }

    private static String body(String lead, JsonNode payload) {
        List<String> lines = new ArrayList<>();
        lines.add(lead);
        lines.add("");
        if (payload.hasNonNull("reservationId")) {
            lines.add("예매 번호: " + payload.get("reservationId").asText());
        }
        lines.add("주문 번호: " + payload.path("orderId").asText());
        if (payload.has("seatIds") && payload.get("seatIds").isArray()) {
            List<String> seats = new ArrayList<>();
            payload.get("seatIds").forEach(seat -> seats.add(seat.asText()));
            lines.add("좌석: " + String.join(", ", seats));
        }
        lines.add("금액: "
                + NumberFormat.getIntegerInstance(Locale.KOREA)
                        .format(payload.path("amount").asLong()) + "원");
        return String.join("\n", lines);
    }

    private record Template(String subject, String lead) {}
}
