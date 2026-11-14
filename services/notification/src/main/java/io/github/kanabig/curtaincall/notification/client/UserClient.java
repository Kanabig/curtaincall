package io.github.kanabig.curtaincall.notification.client;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

/** user 서비스 내부 API. 실패는 예외로 올려 보낸다. 재시도는 호출한 컨슈머의 백오프가 맡는다. */
@Component
public class UserClient {

    private final RestClient restClient;

    public UserClient(@Qualifier("userRestClient") RestClient restClient) {
        this.restClient = restClient;
    }

    public UserContact fetch(long userId) {
        UserContact contact =
                restClient.get().uri("/internal/users/{id}", userId).retrieve().body(UserContact.class);
        if (contact == null || contact.email() == null || contact.email().isBlank()) {
            throw new IllegalStateException("user 응답에 이메일이 없다: userId=" + userId);
        }
        return contact;
    }
}
