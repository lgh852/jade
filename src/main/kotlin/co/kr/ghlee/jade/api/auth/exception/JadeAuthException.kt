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

class JadeLoginFailedException(
    message: String = "아이디 또는 비밀번호가 올바르지 않습니다.",
) : JadeAuthException(message)
