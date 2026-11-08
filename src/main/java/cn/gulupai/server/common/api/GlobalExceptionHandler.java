package cn.gulupai.server.common.api;

import com.fasterxml.jackson.core.JsonParseException;
import com.fasterxml.jackson.databind.JsonMappingException;
import com.fasterxml.jackson.databind.exc.InvalidFormatException;
import com.fasterxml.jackson.databind.exc.UnrecognizedPropertyException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.ConstraintViolationException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.http.converter.HttpMessageNotWritableException;
import org.springframework.validation.FieldError;
import org.springframework.web.HttpMediaTypeNotSupportedException;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingRequestHeaderException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.HandlerMethodValidationException;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.multipart.MaxUploadSizeExceededException;
import org.springframework.web.servlet.NoHandlerFoundException;
import org.springframework.web.servlet.resource.NoResourceFoundException;

import java.util.ArrayList;
import java.util.List;

/**
 * 把所有异常收敛为统一 JSON 信封，禁止 Spring 默认 HTML/problem 响应。
 *
 * <p>日志只记录异常类型、路径和请求 ID，不记录请求正文、口令或令牌。
 */
@RestControllerAdvice
public class GlobalExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    @ExceptionHandler(RetryAfterException.class)
    public ResponseEntity<ApiResponse<?>> handleRetryAfter(RetryAfterException ex) {
        return ResponseEntity.status(ex.code().httpStatus())
                .header(HttpHeaders.RETRY_AFTER, Long.toString(ex.retryAfterSeconds()))
                .body(ApiResponse.error(ex.code(), ex.getMessage()));
    }

    @ExceptionHandler(ApiException.class)
    public ResponseEntity<ApiResponse<?>> handleApiException(ApiException ex) {
        if (!ex.fieldErrors().isEmpty()) {
            return fieldErrors(ex.code(), ex.getMessage(), ex.fieldErrors());
        }
        return error(ex.code(), ex.getMessage());
    }

    @ExceptionHandler(CollectionTooLargeException.class)
    public ResponseEntity<ApiResponse<?>> handleCollectionTooLarge(CollectionTooLargeException ex) {
        return error(ApiCode.COLLECTION_TOO_LARGE, ApiCode.COLLECTION_TOO_LARGE.defaultMessage());
    }

    @ExceptionHandler(ResponseTooLargeException.class)
    public ResponseEntity<ApiResponse<?>> handleResponseTooLarge(ResponseTooLargeException ex) {
        return error(ApiCode.PAYLOAD_TOO_LARGE, ApiCode.PAYLOAD_TOO_LARGE.defaultMessage());
    }

    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<ApiResponse<?>> handleUnreadable(HttpMessageNotReadableException ex) {
        Throwable cause = rootCause(ex);
        if (contains(ex, RequestTooLargeException.class)) {
            return error(ApiCode.PAYLOAD_TOO_LARGE, ApiCode.PAYLOAD_TOO_LARGE.defaultMessage());
        }
        if (contains(ex, UnrecognizedPropertyException.class)) {
            return error(ApiCode.FIELD_NOT_ALLOWED, ApiCode.FIELD_NOT_ALLOWED.defaultMessage());
        }
        if (contains(ex, JsonParseException.class) || cause instanceof java.io.EOFException) {
            return error(ApiCode.MALFORMED_JSON, ApiCode.MALFORMED_JSON.defaultMessage());
        }
        InvalidFormatException format = findCause(ex, InvalidFormatException.class);
        if (format != null) {
            return fieldErrors(ApiCode.VALIDATION_FAILED, "请检查填写内容",
                    List.of(new FieldErrorDetail(fieldName(format), "INVALID_FORMAT", "格式不正确")));
        }
        if (contains(ex, JsonMappingException.class) || cause instanceof IllegalArgumentException) {
            return error(ApiCode.VALIDATION_FAILED, ApiCode.VALIDATION_FAILED.defaultMessage());
        }
        return error(ApiCode.MALFORMED_JSON, ApiCode.MALFORMED_JSON.defaultMessage());
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ApiResponse<?>> handleMethodArgumentNotValid(MethodArgumentNotValidException ex) {
        List<FieldErrorDetail> errors = new ArrayList<>();
        for (FieldError fieldError : ex.getBindingResult().getFieldErrors()) {
            if (errors.size() >= 20) {
                break;
            }
            String code = fieldCode(fieldError.getCode());
            errors.add(new FieldErrorDetail(fieldError.getField(), code, fieldMessage(code)));
        }
        log.debug("request validation failed fields={}",
                errors.stream().map(FieldErrorDetail::field).toList());
        if (errors.isEmpty()) {
            return error(ApiCode.VALIDATION_FAILED, "请检查填写内容");
        }
        return fieldErrors(ApiCode.VALIDATION_FAILED, "请检查填写内容", errors);
    }

    @ExceptionHandler(HandlerMethodValidationException.class)
    public ResponseEntity<ApiResponse<?>> handleHandlerMethodValidation(HandlerMethodValidationException ex) {
        return error(ApiCode.VALIDATION_FAILED, ApiCode.VALIDATION_FAILED.defaultMessage());
    }

    @ExceptionHandler(ConstraintViolationException.class)
    public ResponseEntity<ApiResponse<?>> handleConstraintViolation(ConstraintViolationException ex) {
        return error(ApiCode.VALIDATION_FAILED, ApiCode.VALIDATION_FAILED.defaultMessage());
    }

    @ExceptionHandler({MissingServletRequestParameterException.class,
            MissingRequestHeaderException.class,
            MethodArgumentTypeMismatchException.class})
    public ResponseEntity<ApiResponse<?>> handleBadRequest(Exception ex) {
        return error(ApiCode.MALFORMED_JSON, ApiCode.MALFORMED_JSON.defaultMessage());
    }

    @ExceptionHandler(HttpRequestMethodNotSupportedException.class)
    public ResponseEntity<ApiResponse<?>> handleMethodNotAllowed(HttpRequestMethodNotSupportedException ex) {
        return error(ApiCode.METHOD_NOT_ALLOWED, ApiCode.METHOD_NOT_ALLOWED.defaultMessage());
    }

    @ExceptionHandler(HttpMediaTypeNotSupportedException.class)
    public ResponseEntity<ApiResponse<?>> handleUnsupportedMediaType(HttpMediaTypeNotSupportedException ex) {
        return error(ApiCode.UNSUPPORTED_MEDIA_TYPE, ApiCode.UNSUPPORTED_MEDIA_TYPE.defaultMessage());
    }

    @ExceptionHandler(MaxUploadSizeExceededException.class)
    public ResponseEntity<ApiResponse<?>> handleUploadTooLarge(MaxUploadSizeExceededException ex) {
        return error(ApiCode.PAYLOAD_TOO_LARGE, ApiCode.PAYLOAD_TOO_LARGE.defaultMessage());
    }

    @ExceptionHandler({NoHandlerFoundException.class, NoResourceFoundException.class})
    public ResponseEntity<ApiResponse<?>> handleNotFound(Exception ex, HttpServletRequest request) {
        String uri = request.getRequestURI() == null ? "" : request.getRequestURI();
        if (uri.equals("/api/v1/admin") || uri.startsWith("/api/v1/admin/")) {
            return error(ApiCode.PATH_NOT_FOUND, ApiCode.PATH_NOT_FOUND.defaultMessage());
        }
        return error(ApiCode.NOT_FOUND, ApiCode.NOT_FOUND.defaultMessage());
    }

    @ExceptionHandler(DataIntegrityViolationException.class)
    public ResponseEntity<ApiResponse<?>> handleConstraint(DataIntegrityViolationException ex) {
        String message = ex.getMostSpecificCause() == null ? ex.getMessage()
                : ex.getMostSpecificCause().getMessage();
        String text = message == null ? "" : message;
        if (text.contains("uq_assignment_team_slot")) {
            return error(ApiCode.ALREADY_IN_ASSIGNMENT_TEAM,
                    ApiCode.ALREADY_IN_ASSIGNMENT_TEAM.defaultMessage());
        }
        if (text.contains("uq_team_invitation_pending") || text.contains("uq_team_application_pending")) {
            return error(ApiCode.APPLICATION_EXISTS, ApiCode.APPLICATION_EXISTS.defaultMessage());
        }
        if (text.contains("uq_seat_reservation_active") || text.contains("uq_membership_active")
                || text.contains("uq_role_slot_position") || text.contains("uq_project_code_active")) {
            return error(ApiCode.RESOURCE_CHANGED, ApiCode.RESOURCE_CHANGED.defaultMessage());
        }
        log.error("database constraint violation", ex);
        return error(ApiCode.RESOURCE_CHANGED, ApiCode.RESOURCE_CHANGED.defaultMessage());
    }

    @ExceptionHandler(HttpMessageNotWritableException.class)
    public ResponseEntity<ApiResponse<?>> handleNotWritable(HttpMessageNotWritableException ex) {
        if (contains(ex, CollectionTooLargeException.class)) {
            return error(ApiCode.COLLECTION_TOO_LARGE, ApiCode.COLLECTION_TOO_LARGE.defaultMessage());
        }
        if (contains(ex, ResponseTooLargeException.class)) {
            return error(ApiCode.PAYLOAD_TOO_LARGE, ApiCode.PAYLOAD_TOO_LARGE.defaultMessage());
        }
        log.error("response serialization failed", ex);
        return error(ApiCode.SERVER_ERROR, ApiCode.SERVER_ERROR.defaultMessage());
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<ApiResponse<?>> handleUnexpected(Exception ex, HttpServletRequest request) {
        log.error("unhandled exception requestId={} method={} path={}",
                RequestIds.current(), request.getMethod(), request.getRequestURI(), ex);
        return error(ApiCode.SERVER_ERROR, ApiCode.SERVER_ERROR.defaultMessage());
    }

    private ResponseEntity<ApiResponse<?>> error(ApiCode code, String message) {
        ApiResponse<?> body = ApiResponse.error(code, message);
        return ResponseEntity.status(code.httpStatus()).body(body);
    }

    private ResponseEntity<ApiResponse<?>> fieldErrors(ApiCode code, String message,
                                                       List<FieldErrorDetail> errors) {
        List<FieldErrorDetail> limited = errors.size() <= 20 ? errors : errors.subList(0, 20);
        return ResponseEntity.status(code.httpStatus())
                .body(ApiResponse.fail(code, message, new FieldErrorBody(limited)));
    }

    private static String fieldCode(String springCode) {
        if (springCode == null) {
            return "INVALID_FORMAT";
        }
        return switch (springCode) {
            case "NotNull", "NotBlank", "NotEmpty" -> "REQUIRED";
            case "Size", "Utf16Length" -> "TOO_LONG";
            case "Min", "Max", "Positive", "PositiveOrZero", "DecimalMin", "DecimalMax" -> "OUT_OF_RANGE";
            default -> "INVALID_FORMAT";
        };
    }

    private static String fieldMessage(String code) {
        return switch (code) {
            case "REQUIRED" -> "请填写此项";
            case "TOO_LONG" -> "超出长度限制";
            case "OUT_OF_RANGE" -> "超出允许范围";
            case "DUPLICATE" -> "存在重复项";
            default -> "格式不正确";
        };
    }

    private static String fieldName(InvalidFormatException exception) {
        if (exception.getPath() == null || exception.getPath().isEmpty()) {
            return "body";
        }
        String name = exception.getPath().get(exception.getPath().size() - 1).getFieldName();
        return name == null || name.isBlank() ? "body" : name;
    }

    private static <T extends Throwable> T findCause(Throwable throwable, Class<T> type) {
        Throwable current = throwable;
        int guard = 0;
        while (current != null && guard++ < 20) {
            if (type.isInstance(current)) {
                return type.cast(current);
            }
            current = current.getCause();
        }
        return null;
    }

    private static Throwable rootCause(Throwable throwable) {
        Throwable current = throwable;
        int guard = 0;
        while (current.getCause() != null && current.getCause() != current && guard++ < 20) {
            current = current.getCause();
        }
        return current;
    }

    private static boolean contains(Throwable throwable, Class<? extends Throwable> type) {
        Throwable current = throwable;
        int guard = 0;
        while (current != null && guard++ < 20) {
            if (type.isInstance(current)) {
                return true;
            }
            current = current.getCause();
        }
        return false;
    }
}
