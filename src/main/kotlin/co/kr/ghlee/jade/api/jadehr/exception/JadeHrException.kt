package co.kr.ghlee.jade.api.jadehr.exception

sealed class JadeHrException(
    message: String,
    cause: Throwable? = null,
) : RuntimeException(message, cause)

class JadeHrLoginException(
    message: String,
) : JadeHrException(message)

class JadeHrTwoFactorRequiredException(
    message: String = "JadeHR 2FA is required.",
) : JadeHrException(message)

class JadeHrParseException(
    message: String,
    cause: Throwable? = null,
) : JadeHrException(message, cause)
