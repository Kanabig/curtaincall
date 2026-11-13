package io.github.kanabig.curtaincall.common.web.error;

import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.web.ErrorResponse;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.context.request.WebRequest;
import org.springframework.web.servlet.mvc.method.annotation.ResponseEntityExceptionHandler;

/** 모든 에러를 RFC 9457 ProblemDetail에 {@code code}를 붙여 응답한다. */
@RestControllerAdvice
public class GlobalExceptionHandler extends ResponseEntityExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    @ExceptionHandler(BusinessException.class)
    public ResponseEntity<Object> handleBusiness(BusinessException e, WebRequest request) {
        ErrorCode errorCode = e.errorCode();
        ProblemDetail problem = problem(errorCode, e.getMessage());
        e.properties().forEach(problem::setProperty);
        return handleExceptionInternal(e, problem, new HttpHeaders(), errorCode.status(), request);
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<Object> handleUnexpected(Exception e, WebRequest request) {
        // 내부 사유는 응답에 싣지 않고 로그에만 남긴다
        log.error("Unhandled exception", e);
        ErrorCode errorCode = CommonErrorCode.INTERNAL_ERROR;
        return handleExceptionInternal(
                e, problem(errorCode, errorCode.message()), new HttpHeaders(), errorCode.status(), request);
    }

    @Override
    protected ResponseEntity<Object> handleMethodArgumentNotValid(
            MethodArgumentNotValidException e, HttpHeaders headers, HttpStatusCode status, WebRequest request) {
        ProblemDetail problem = problem(CommonErrorCode.INVALID_REQUEST, "요청 값이 올바르지 않습니다");
        List<FieldViolation> errors = e.getBindingResult().getFieldErrors().stream()
                .map(f -> new FieldViolation(f.getField(), f.getDefaultMessage()))
                .toList();
        problem.setProperty("errors", errors);
        return handleExceptionInternal(e, problem, headers, status, request);
    }

    @Override
    protected ResponseEntity<Object> handleExceptionInternal(
            Exception e, Object body, HttpHeaders headers, HttpStatusCode status, WebRequest request) {
        // Spring MVC 예외는 body 없이 넘어오므로 ErrorResponse의 ProblemDetail을 꺼내 code를 채운다
        Object resolved = body;
        if (resolved == null && e instanceof ErrorResponse errorResponse) {
            resolved = errorResponse.getBody();
        }
        if (resolved instanceof ProblemDetail problem && !hasCode(problem)) {
            problem.setProperty("code", fallbackCode(status).code());
        }
        return super.handleExceptionInternal(e, resolved, headers, status, request);
    }

    private static ProblemDetail problem(ErrorCode errorCode, String detail) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(errorCode.status(), detail);
        problem.setTitle(errorCode.message());
        problem.setProperty("code", errorCode.code());
        return problem;
    }

    private static boolean hasCode(ProblemDetail problem) {
        return problem.getProperties() != null && problem.getProperties().containsKey("code");
    }

    private static ErrorCode fallbackCode(HttpStatusCode status) {
        if (status.value() == HttpStatus.NOT_FOUND.value()) {
            return CommonErrorCode.NOT_FOUND;
        }
        if (status.value() == HttpStatus.METHOD_NOT_ALLOWED.value()) {
            return CommonErrorCode.METHOD_NOT_ALLOWED;
        }
        if (status.value() == HttpStatus.UNSUPPORTED_MEDIA_TYPE.value()) {
            return CommonErrorCode.UNSUPPORTED_MEDIA_TYPE;
        }
        return status.is4xxClientError() ? CommonErrorCode.INVALID_REQUEST : CommonErrorCode.INTERNAL_ERROR;
    }

    record FieldViolation(String field, String message) {}
}
