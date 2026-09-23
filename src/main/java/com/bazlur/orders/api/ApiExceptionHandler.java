package com.bazlur.orders.api;

import module java.base;
import module java.sql;

import com.bazlur.orders.application.OrderException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/** Maps failures to RFC 9457 problem details without exposing SQL messages. */
@RestControllerAdvice
public class ApiExceptionHandler {
    private static final Logger LOG = LoggerFactory.getLogger(ApiExceptionHandler.class);

    // MySQL error codes that mean "someone else changed this first".
    private static final int STALE_ETAG = 6494;
    private static final int DEADLOCK = 1213;
    private static final int LOCK_WAIT_TIMEOUT = 1205;

    @ExceptionHandler(OrderException.class)
    ResponseEntity<ProblemDetail> orderError(OrderException error) {
        return problem(status(error.kind()), error.getMessage());
    }

    @ExceptionHandler(SQLException.class)
    ResponseEntity<ProblemDetail> databaseError(SQLException error) {
        LOG.error("Database error {} ({})", error.getErrorCode(), error.getSQLState(), error);
        return switch (error.getErrorCode()) {
            case STALE_ETAG, DEADLOCK, LOCK_WAIT_TIMEOUT ->
                    problem(HttpStatus.CONFLICT, "Concurrent change; fetch the order again before editing");
            default -> problem(HttpStatus.INTERNAL_SERVER_ERROR, "Database operation failed");
        };
    }

    // Exhaustive: adding a Kind does not compile until it has an HTTP status.
    static HttpStatus status(OrderException.Kind kind) {
        return switch (kind) {
            case INVALID_REQUEST -> HttpStatus.BAD_REQUEST;
            case NOT_FOUND -> HttpStatus.NOT_FOUND;
            case MISSING_TOKEN -> HttpStatus.PRECONDITION_REQUIRED;
            case STALE -> HttpStatus.CONFLICT;
            case NOT_ALLOWED -> HttpStatus.UNPROCESSABLE_CONTENT;
            case TOO_LARGE -> HttpStatus.CONTENT_TOO_LARGE;
        };
    }

    private static ResponseEntity<ProblemDetail> problem(HttpStatus status, String detail) {
        return ResponseEntity.status(status).cacheControl(CacheControl.noStore())
                .body(ProblemDetail.forStatusAndDetail(status, detail));
    }
}
