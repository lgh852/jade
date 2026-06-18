package co.kr.ghlee.jade.api.jadehr.domain

data class JadeHrSession(
    val cookies: Map<String, String>,
) {
    fun isEmpty(): Boolean = cookies.isEmpty()
}
