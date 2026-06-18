package co.kr.ghlee.jade.api.jadehr.infrastructure.client

import org.springframework.http.ResponseCookie
import org.springframework.util.MultiValueMap

class JadeHrCookieJar {
    private val cookies = linkedMapOf<String, String>()

    fun put(
        name: String,
        value: String,
    ) {
        cookies[name] = value
    }

    fun addTo(target: MultiValueMap<String, String>) {
        cookies.forEach { (name, value) -> target.add(name, value) }
    }

    fun update(responseCookies: MultiValueMap<String, ResponseCookie>) {
        responseCookies.forEach { (name, values) ->
            values.lastOrNull()?.value
                ?.takeIf { it.isNotBlank() }
                ?.let { cookies[name] = it }
        }
    }

    fun snapshot(): Map<String, String> = cookies.toMap()
}
