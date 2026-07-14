package pl.kacper.sales_api.common.exceptionHandler;

import io.jsonwebtoken.ExpiredJwtException;
import org.hibernate.OrderingMode;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.validation.BindingResult;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import pl.kacper.sales_api.common.dto.InvalidParamDto;
import pl.kacper.sales_api.common.exception.DuplicateUsernameException;
import pl.kacper.sales_api.common.exception.NoSuchDbRecordException;

import java.util.List;

@RestControllerAdvice()
@Order(Ordered.HIGHEST_PRECEDENCE)
public class GlobalExceptionHandler {

    @ExceptionHandler(DuplicateUsernameException.class)
    public ProblemDetail handleDuplicateUsernameException(Throwable throwable) {
        ProblemDetail problemDetail = ProblemDetail.forStatusAndDetail(
                HttpStatus.CONFLICT,
                throwable.getMessage()
        );

        problemDetail.setTitle("User registration failed");

        return problemDetail;
    }

    @ExceptionHandler({IllegalArgumentException.class})
    public ProblemDetail handleIllegalArgumentException(Throwable throwable) {
        ProblemDetail problemDetail = ProblemDetail.forStatusAndDetail(
                HttpStatus.BAD_REQUEST,
                throwable.getMessage()
        );

        problemDetail.setTitle("Invalid arguments");

        return problemDetail;
    }

    @ExceptionHandler({NoSuchDbRecordException.class})
    public ProblemDetail handleNoResourceException(Throwable throwable){
        ProblemDetail problemDetail = ProblemDetail.forStatusAndDetail(
                HttpStatus.NOT_FOUND,
                throwable.getMessage()
        );

        problemDetail.setTitle("Resource not exists");

        return problemDetail;
    }

    @ExceptionHandler(ExpiredJwtException.class)
    public ProblemDetail handleExpiredJwtException(Throwable throwable) {
        ProblemDetail problemDetail = ProblemDetail.forStatusAndDetail(
                HttpStatus.FORBIDDEN,
                "Your session has expired. Try to login again to get access"
        );

        problemDetail.setTitle("Session expired");

        return problemDetail;
    }

    @ExceptionHandler({MethodArgumentNotValidException.class})
    public ProblemDetail handleParamValidationException(BindingResult bindingResult) {
        ProblemDetail problemDetail = ProblemDetail.forStatusAndDetail(
                HttpStatus.BAD_REQUEST,
                "Request contains invalid parameters"
        );

        problemDetail.setTitle("Validation Failed");

        List<InvalidParamDto> invalidParamList = bindingResult.getFieldErrors()
                .stream()
                .map(this::convertToInvalidParamDto)
                .toList();

        problemDetail.setProperty("invalidParams", invalidParamList);

        return problemDetail;
    }

    private InvalidParamDto convertToInvalidParamDto(FieldError fieldError) {
        return new InvalidParamDto(
                fieldError.getField(),
                fieldError.getDefaultMessage()
        );
    }
}
