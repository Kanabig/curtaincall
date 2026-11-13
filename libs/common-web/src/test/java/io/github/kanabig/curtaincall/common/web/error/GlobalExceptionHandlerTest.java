package io.github.kanabig.curtaincall.common.web.error;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

class GlobalExceptionHandlerTest {

    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders.standaloneSetup(new TestController())
                .setControllerAdvice(new GlobalExceptionHandler())
                .build();
    }

    @Test
    @DisplayName("BusinessException은 상태, code, 추가 필드를 담은 problem+json으로 응답한다")
    void businessException() throws Exception {
        mockMvc.perform(get("/business"))
                .andExpect(status().isConflict())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.status").value(409))
                .andExpect(jsonPath("$.code").value("SEAT_ALREADY_HELD"))
                .andExpect(jsonPath("$.title").value("Seat already held"))
                .andExpect(jsonPath("$.detail").value("좌석 1개를 다른 사용자가 점유 중"))
                .andExpect(jsonPath("$.seatIds[0]").value(10231));
    }

    @Test
    @DisplayName("검증 실패는 INVALID_REQUEST와 필드별 사유를 응답한다")
    void validationFailure() throws Exception {
        mockMvc.perform(post("/validate")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_REQUEST"))
                .andExpect(jsonPath("$.errors[0].field").value("name"));
    }

    @Test
    @DisplayName("Spring MVC가 만든 404, 405 응답에도 code가 붙는다")
    void springMvcErrorsHaveCode() throws Exception {
        mockMvc.perform(post("/business"))
                .andExpect(status().isMethodNotAllowed())
                .andExpect(jsonPath("$.code").value("METHOD_NOT_ALLOWED"));
    }

    @Test
    @DisplayName("예상하지 못한 예외는 내부 사유를 숨기고 500 INTERNAL_ERROR로 응답한다")
    void unexpectedException() throws Exception {
        mockMvc.perform(get("/boom"))
                .andExpect(status().isInternalServerError())
                .andExpect(jsonPath("$.code").value("INTERNAL_ERROR"))
                .andExpect(jsonPath("$.detail").value("Internal server error"));
    }

    enum TestErrorCode implements ErrorCode {
        SEAT_ALREADY_HELD(HttpStatus.CONFLICT, "Seat already held");

        private final HttpStatus status;
        private final String message;

        TestErrorCode(HttpStatus status, String message) {
            this.status = status;
            this.message = message;
        }

        @Override
        public String code() {
            return name();
        }

        @Override
        public HttpStatus status() {
            return status;
        }

        @Override
        public String message() {
            return message;
        }
    }

    record NameRequest(@NotBlank String name) {}

    @RestController
    static class TestController {

        @GetMapping("/business")
        void business() {
            throw new BusinessException(TestErrorCode.SEAT_ALREADY_HELD, "좌석 1개를 다른 사용자가 점유 중")
                    .with("seatIds", List.of(10231));
        }

        @PostMapping("/validate")
        void validate(@Valid @RequestBody NameRequest request) {}

        @GetMapping("/boom")
        void boom() {
            throw new IllegalStateException("db password is secret");
        }
    }
}
