package co.kr.ghlee.jade.common.api

import co.kr.ghlee.jade.api.auth.exception.JadeAuthRequiredException
import co.kr.ghlee.jade.api.auth.exception.JadeAuthUserNotFoundException
import co.kr.ghlee.jade.api.jadehr.exception.JadeHrLoginException
import co.kr.ghlee.jade.api.jadehr.exception.JadeHrParseException
import co.kr.ghlee.jade.api.jadehr.exception.JadeHrSessionRequiredException
import co.kr.ghlee.jade.api.jadehr.exception.JadeHrTwoFactorRequiredException
import org.slf4j.LoggerFactory
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.MethodArgumentNotValidException
import org.springframework.web.bind.annotation.ExceptionHandler
import org.springframework.web.bind.annotation.RestControllerAdvice
import org.springframework.web.reactive.function.client.WebClientRequestException
import org.springframework.web.server.ServerWebInputException

@RestControllerAdvice
class GlobalExceptionHandler {

    @ExceptionHandler(JadeAuthRequiredException::class)
    fun handleJadeAuthRequiredException(exception: JadeAuthRequiredException): ResponseEntity<ApiErrorResponse> =
        ResponseEntity.status(HttpStatus.UNAUTHORIZED)
            .body(ApiErrorResponse("JADE_AUTH_REQUIRED", exception.message ?: "Jade auth key is required."))

    @ExceptionHandler(JadeAuthUserNotFoundException::class)
    fun handleJadeAuthUserNotFoundException(exception: JadeAuthUserNotFoundException): ResponseEntity<ApiErrorResponse> =
        ResponseEntity.status(HttpStatus.UNAUTHORIZED)
            .body(ApiErrorResponse("JADE_AUTH_USER_NOT_FOUND", exception.message ?: "Jade auth user was not found."))

    @ExceptionHandler(JadeHrLoginException::class)
    fun handleLoginException(exception: JadeHrLoginException): ResponseEntity<ApiErrorResponse> {
        log.warn("JadeHR login failed. message={}", exception.message)
        return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
            .body(ApiErrorResponse("JADEHR_LOGIN_FAILED", exception.message ?: "JadeHR login failed."))
    }

    @ExceptionHandler(JadeHrTwoFactorRequiredException::class)
    fun handleTwoFactorException(exception: JadeHrTwoFactorRequiredException): ResponseEntity<ApiErrorResponse> =
        ResponseEntity.status(HttpStatus.CONFLICT)
            .body(ApiErrorResponse("JADEHR_2FA_REQUIRED", exception.message ?: "JadeHR 2FA is required."))

    @ExceptionHandler(JadeHrSessionRequiredException::class)
    fun handleSessionRequiredException(exception: JadeHrSessionRequiredException): ResponseEntity<ApiErrorResponse> =
        ResponseEntity.status(HttpStatus.UNAUTHORIZED)
            .body(ApiErrorResponse("JADEHR_SESSION_REQUIRED", exception.message ?: "JadeHR session is required."))

    @ExceptionHandler(JadeHrParseException::class)
    fun handleParseException(exception: JadeHrParseException): ResponseEntity<ApiErrorResponse> {
        log.warn("JadeHR parse failed. message={}", exception.message)
        return ResponseEntity.status(HttpStatus.BAD_GATEWAY)
            .body(ApiErrorResponse("JADEHR_PARSE_FAILED", exception.message ?: "JadeHR response parsing failed."))
    }

    @ExceptionHandler(WebClientRequestException::class)
    fun handleWebClientRequestException(exception: WebClientRequestException): ResponseEntity<ApiErrorResponse> {
        log.warn("JadeHR connection failed.", exception)
        return ResponseEntity.status(HttpStatus.BAD_GATEWAY)
            .body(ApiErrorResponse("JADEHR_CONNECTION_FAILED", exception.rootCauseMessage()))
    }

    @ExceptionHandler(MethodArgumentNotValidException::class, ServerWebInputException::class)
    fun handleBadRequest(exception: Exception): ResponseEntity<ApiErrorResponse> =
        ResponseEntity.badRequest()
            .body(ApiErrorResponse("BAD_REQUEST", exception.message ?: "Invalid request."))

    private fun WebClientRequestException.rootCauseMessage(): String {
        var current: Throwable = this
        while (current.cause != null) {
            current = current.cause!!
        }
        return current.message ?: message ?: "JadeHR connection failed."
    }

    companion object {
        private val log = LoggerFactory.getLogger(GlobalExceptionHandler::class.java)
    }
}

data class ApiErrorResponse(
    val code: String,
    val message: String,
)
