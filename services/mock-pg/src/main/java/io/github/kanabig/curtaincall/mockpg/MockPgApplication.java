package io.github.kanabig.curtaincall.mockpg;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/** PAY-2: payment의 PG 클라이언트(timeout, 서킷)를 실제 HTTP 경계에서 확인하기 위한 가짜 PG. */
@SpringBootApplication
public class MockPgApplication {

    public static void main(String[] args) {
        SpringApplication.run(MockPgApplication.class, args);
    }
}
