package co.kr.ghlee.jade.api.auth.exception

sealed class JadeAuthException(
    message: String,
    cause: Throwable? = null,
) : RuntimeException(message, cause)

class JadeAuthRequiredException(
    message: String = "Jade auth key is required.",
) : JadeAuthException(message)

class JadeAuthUserNotFoundException(
    message: String = "Jade auth user was not found.",
) : JadeAuthException(message)
