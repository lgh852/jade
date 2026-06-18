package co.kr.ghlee.jade.api.jadehr.application

import co.kr.ghlee.jade.api.jadehr.domain.AttendanceRecordResult
import co.kr.ghlee.jade.api.jadehr.domain.AttendanceRecordType
import co.kr.ghlee.jade.api.jadehr.infrastructure.config.TelegramNotificationProperties
import kotlinx.coroutines.reactor.awaitSingleOrNull
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import org.springframework.web.reactive.function.BodyInserters
import org.springframework.web.reactive.function.client.WebClient

@Service
class AttendanceRecordNotificationService(
    private val webClientBuilder: WebClient.Builder,
    private val properties: TelegramNotificationProperties,
) {

    suspend fun send(
        nickname: String,
        type: AttendanceRecordType,
        result: AttendanceRecordResult,
    ) {
        if (properties.botToken.isBlank() || properties.chatId.isBlank()) {
            return
        }

        val text = buildMessage(nickname, type, result)
        runCatching {
            webClientBuilder
                .baseUrl("https://api.telegram.org")
                .build()
                .post()
                .uri("/bot{token}/sendMessage", properties.botToken)
                .body(
                    BodyInserters.fromFormData("chat_id", properties.chatId)
                        .with("text", text)
                )
                .retrieve()
                .bodyToMono(String::class.java)
                .awaitSingleOrNull()
        }.onFailure { exception ->
            log.warn("Telegram attendance notification failed. type={}, nickname={}", type, nickname, exception)
        }
    }

    private fun buildMessage(
        nickname: String,
        type: AttendanceRecordType,
        result: AttendanceRecordResult,
    ): String {
        val action = when (type) {
            AttendanceRecordType.START -> "출근"
            AttendanceRecordType.END -> "퇴근"
        }
        val status = if (result.success) "성공" else "실패"
        val duplicate = result.etcData["DUPLICATE_SKIPPED"] == "Y"
        val suffix = if (duplicate) " (이미 처리됨)" else ""
        val message = result.message?.takeIf { it.isNotBlank() } ?: "-"

        return "[$nickname] $action $status$suffix\n$message"
    }

    companion object {
        private val log = LoggerFactory.getLogger(AttendanceRecordNotificationService::class.java)
    }
}
