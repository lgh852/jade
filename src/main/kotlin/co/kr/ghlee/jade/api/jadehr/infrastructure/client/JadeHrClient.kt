package co.kr.ghlee.jade.api.jadehr.infrastructure.client

import co.kr.ghlee.jade.api.jadehr.infrastructure.config.JadeHrProperties
import co.kr.ghlee.jade.api.jadehr.domain.AttendanceRecordPrepareResult
import co.kr.ghlee.jade.api.jadehr.domain.AttendanceRecordResult
import co.kr.ghlee.jade.api.jadehr.domain.AttendanceRecordType
import co.kr.ghlee.jade.api.jadehr.domain.JadeHrCredential
import co.kr.ghlee.jade.api.jadehr.domain.JadeHrSession
import co.kr.ghlee.jade.api.jadehr.exception.JadeHrLoginException
import co.kr.ghlee.jade.api.jadehr.exception.JadeHrParseException
import co.kr.ghlee.jade.api.jadehr.exception.JadeHrSessionRequiredException
import co.kr.ghlee.jade.api.jadehr.exception.JadeHrTwoFactorRequiredException
import kotlinx.coroutines.reactor.awaitSingle
import org.jsoup.Jsoup
import org.jsoup.parser.Parser
import org.slf4j.LoggerFactory
import org.springframework.http.MediaType
import org.springframework.stereotype.Component
import org.springframework.util.LinkedMultiValueMap
import org.springframework.util.MultiValueMap
import org.springframework.web.reactive.function.BodyInserters
import org.springframework.web.reactive.function.client.ClientResponse
import org.springframework.web.reactive.function.client.WebClient
import reactor.core.publisher.Mono
import java.net.URLEncoder
import java.security.MessageDigest
import java.time.LocalDate
import java.time.LocalTime
import java.time.YearMonth
import java.time.ZoneId
import java.time.format.DateTimeFormatter

@Component
class JadeHrClient(
    private val webClient: WebClient,
    private val properties: JadeHrProperties,
) {

    suspend fun login(credential: JadeHrCredential): JadeHrSession {
        val cookieJar = JadeHrCookieJar()

        webClient.get()
            .uri("/")
            .exchangeToBody(cookieJar)

        val loginBefore = postCommonAction(
            cookieJar = cookieJar,
            form = loginForm(credential, "login_before"),
        )
        loginBefore.message?.takeIf { it.isNotBlank() }?.let { throw JadeHrLoginException(it) }

        val twoFactorYn = loginBefore.etcData["AUTH_2FA_USE_YN"]
        if (twoFactorYn == "Y") {
            throw JadeHrTwoFactorRequiredException()
        }

        val login = postCommonAction(
            cookieJar = cookieJar,
            form = loginForm(credential, "login"),
        )
        login.message?.takeIf { it.isNotBlank() }?.let { throw JadeHrLoginException(it) }

        webClient.get()
            .uri("/")
            .cookies(cookieJar::addTo)
            .exchangeToBody(cookieJar)

        return JadeHrSession(cookieJar.snapshot())
    }

    suspend fun fetchAttendanceCalendarHtml(
        session: JadeHrSession,
        yearMonth: YearMonth,
    ): String {
        val cookieJar = JadeHrCookieJar().apply {
            session.cookies.forEach { (name, value) ->
                put(name, value)
            }
        }

        val mainHtml = webClient.get()
            .uri("/")
            .cookies(cookieJar::addTo)
            .exchangeToBody(cookieJar)
        val menu = AttendanceMenuMetadata.parse(mainHtml)
            .withLoginInfo(mainHtml.extractJavaScriptString("_LOGIN_INFO"))
        log.warn(
            "Fetched JadeHR main page for menu metadata. htmlLength={}, title={}, bodyPreview={}",
            mainHtml.length,
            Jsoup.parse(mainHtml).title(),
            Jsoup.parse(mainHtml).body().text().take(500),
        )
        log.warn("Resolved JadeHR attendance menu metadata. menu={}", menu)

        logProgram(cookieJar, menu)

        val pageHtml = webClient.post()
            .uri("/menuAction.do")
            .header("Origin", properties.baseUrl)
            .header("Referer", "${properties.baseUrl}/")
            .contentType(MediaType.APPLICATION_FORM_URLENCODED)
            .cookies(cookieJar::addTo)
            .body(BodyInserters.fromFormData(menuActionForm(menu, yearMonth)))
            .exchangeToBody(cookieJar)

        log.debug(
            "Fetched JadeHR attendance page. yearMonth={}, htmlLength={}, title={}, bodyPreview={}",
            yearMonth,
            pageHtml.length,
            Jsoup.parse(pageHtml).title(),
            Jsoup.parse(pageHtml).body().text().take(500),
        )
        log.debug("Fetched JadeHR attendance page raw HTML. yearMonth={}, html={}", yearMonth, pageHtml)

        if (pageHtml.contains("<title>::logout::</title>", ignoreCase = true)) {
            throw JadeHrSessionRequiredException("JadeHR session is expired. Please login again.")
        }

        val page = AttendancePageMetadata.parse(pageHtml, menu)
        log.warn(
            "Resolved JadeHR attendance metadata. dsClass={}, calendarMethod={}, employeeId={}, pageEncValue={}, methodCandidates={}",
            page.dsClass,
            page.calendarMethod,
            page.employeeId,
            page.pageEncValue,
            page.methodCandidates,
        )

        val calendarForm = calendarForm(page, yearMonth)
        log.warn("JadeHR attendance calendar request form={}", calendarForm.toSingleValueMap())

        val calendar = postCommonAction(
            cookieJar = cookieJar,
            form = calendarForm,
        )

        val calendarHtml = calendar.etcData["CAL_HTML"]
            ?.takeIf { it.isNotBlank() }
            ?: run {
                log.warn(
                    "JadeHR calendar HTML missing. method={}, etcKeys={}, message={}, responsePreview={}",
                    page.calendarMethod,
                    calendar.etcData.keys,
                    calendar.message,
                    calendar.rawXml.take(1_000),
                )
                throw JadeHrParseException("JadeHR calendar HTML was not found in CAL_HTML.")
            }

        return "<table><tbody>$calendarHtml</tbody></table>"
    }

    suspend fun prepareAttendanceRecord(
        session: JadeHrSession,
        type: AttendanceRecordType,
    ): AttendanceRecordPrepareResult {
        val context = loadMainContext(session)
        val mainInfo = fetchHomeMainInfo(context)
        val popupHtml = openAttendanceRecordPopup(context, type, mainInfo)
        val popup = AttendanceRecordPopupMetadata.parse(popupHtml)
        val form = attendanceRecordForm(popup, type)

        return AttendanceRecordPrepareResult(
            type = type,
            workDate = form.getFirst("S_YMD"),
            employeeId = form.getFirst("S_EMP_ID"),
            gubun = form.getFirst("S_GUBUN").orEmpty(),
            dsClass = popup.dsClass,
            recordMethod = popup.recordMethod,
            validateMethod = popup.validateMethod,
            form = form.toSingleValueMap(),
        )
    }

    suspend fun saveAttendanceRecord(
        session: JadeHrSession,
        type: AttendanceRecordType,
    ): AttendanceRecordResult {
        val context = loadMainContext(session)
        val mainInfo = fetchHomeMainInfo(context)
        val popupHtml = openAttendanceRecordPopup(context, type, mainInfo)
        val popup = AttendanceRecordPopupMetadata.parse(popupHtml)
        val form = attendanceRecordForm(popup, type)
        var validationMessage: String? = null
        var validationEtcData: Map<String, String> = emptyMap()

        popup.validateMethod?.let { validateMethod ->
            val validateForm = LinkedMultiValueMap<String, String>().apply {
                form.toSingleValueMap().forEach { (key, value) -> set(key, value) }
                set("S_DSMETHOD", validateMethod)
            }
            val validation = postCommonAction(context.cookieJar, validateForm)
            validationEtcData = validation.etcData
            validationMessage = validation.etcData["CONFIRM"]?.takeIf { it.isNotBlank() && it != "OK" }
            val blockingMessage = validation.etcData["ALERT_MSG"]?.takeIf { it.isNotBlank() }
            if (blockingMessage != null) {
                return AttendanceRecordResult(
                    type = type,
                    success = false,
                    message = blockingMessage,
                    validationMessage = validationMessage,
                    etcData = validation.etcData,
                )
            }
        }

        if (type == AttendanceRecordType.START) {
            val registeredStartTime = validationEtcData.registeredStartTime()
                ?: form.getFirst("S_C_IN_HM")?.toLocalTimeOrNull()
            val now = LocalTime.now(SEOUL_ZONE)

            if (registeredStartTime != null && registeredStartTime.isBefore(now)) {
                return AttendanceRecordResult(
                    type = type,
                    success = false,
                    message = "이미 현재 시간보다 빠른 출근 시간이 등록되어 있습니다. registeredStartTime=${registeredStartTime.format(DISPLAY_TIME_FORMAT)}",
                    validationMessage = validationMessage,
                    etcData = validationEtcData,
                )
            }
        }

        val saved = postCommonAction(context.cookieJar, form)
        val alertMessage = saved.etcData["ALERT_MSG"]
        return AttendanceRecordResult(
            type = type,
            success = saved.message.isNullOrBlank() && alertMessage.isNullOrBlank(),
            message = alertMessage ?: saved.message,
            validationMessage = validationMessage,
            etcData = saved.etcData,
        )
    }

    private suspend fun loadMainContext(session: JadeHrSession): JadeHrMainContext {
        val cookieJar = JadeHrCookieJar().apply {
            session.cookies.forEach { (name, value) -> put(name, value) }
        }
        val mainHtml = webClient.get()
            .uri("/")
            .cookies(cookieJar::addTo)
            .exchangeToBody(cookieJar)
        val page = MainPageMetadata.parse(mainHtml)
        val menu = AttendanceMenuMetadata.parse(mainHtml)
            .withLoginInfo(mainHtml.extractJavaScriptString("_LOGIN_INFO"))

        return JadeHrMainContext(cookieJar = cookieJar, mainHtml = mainHtml, page = page, menu = menu)
    }

    private suspend fun openAttendanceRecordPopup(
        context: JadeHrMainContext,
        type: AttendanceRecordType,
        mainInfo: HomeMainInfo,
    ): String {
        val form = attendanceRecordPopupForm(context, type, mainInfo)
        return webClient.post()
            .uri("/menuAction.do")
            .header("Origin", properties.baseUrl)
            .header("Referer", "${properties.baseUrl}/")
            .contentType(MediaType.APPLICATION_FORM_URLENCODED)
            .cookies(context.cookieJar::addTo)
            .body(BodyInserters.fromFormData(form))
            .exchangeToBody(context.cookieJar)
    }

    private suspend fun fetchHomeMainInfo(context: JadeHrMainContext): HomeMainInfo {
        val method = context.page.getMainInfoMethod
            ?: return HomeMainInfo.EMPTY.also {
                log.warn("JadeHR home getMainInfo method was not found. Attendance record popup params may be incomplete.")
            }

        val form = LinkedMultiValueMap<String, String>().apply {
            set("S_DSCLASS", context.page.dsClass)
            set("S_DSMETHOD", method)
            set("S_NEWS_COND", "ALL")
            set("S_FORWARD", "xsheetResultXML")
        }

        val response = postCommonAction(context.cookieJar, form)
        val mainInfo = HomeMainInfo.from(response)
        if (mainInfo.tmpInfo.isEmpty()) {
            log.warn(
                "JadeHR home mainInfo tmpInfo was not parsed. etcKeys={}, responsePreview={}",
                response.etcData.keys,
                response.rawXml.take(2_000),
            )
        } else {
            log.warn("Resolved JadeHR home mainInfo. tmpInfo={}, empInfo={}", mainInfo.tmpInfo, mainInfo.empInfo)
        }
        return mainInfo
    }

    private suspend fun postCommonAction(
        cookieJar: JadeHrCookieJar,
        form: MultiValueMap<String, String>,
    ): XSheetResponse {
        addViewState(form)
        log.warn(
            "JadeHR commonAction request form with viewState. method={}, form={}",
            form.getFirst("S_DSMETHOD"),
            form.toSingleValueMap(),
        )
        val response = webClient.post()
            .uri("/commonAction.do")
            .header("ajax", "true")
            .header("X-Requested-With", "XMLHttpRequest")
            .header("Origin", properties.baseUrl)
            .header("Referer", "${properties.baseUrl}/ess/tam4/ess_tam_401_m02.jsp")
            .contentType(MediaType.APPLICATION_FORM_URLENCODED)
            .cookies(cookieJar::addTo)
            .body(BodyInserters.fromFormData(form))
            .exchangeToMono { response ->
                cookieJar.update(response.cookies())
                response.bodyToMono(String::class.java)
            }
            .awaitSingle()

        log.debug(
            "JadeHR commonAction response. method={}, responseLength={}, message={}",
            form.getFirst("S_DSMETHOD"),
            response.length,
            XSheetResponse.parse(response).message,
        )
        log.debug(
            "JadeHR commonAction raw response. method={}, response={}",
            form.getFirst("S_DSMETHOD"),
            response,
        )

        return XSheetResponse.parse(response)
    }

    private suspend fun logProgram(
        cookieJar: JadeHrCookieJar,
        menu: AttendanceMenuMetadata,
    ) {
        val form = LinkedMultiValueMap<String, String>().apply {
            add("S_PROFILE_ID", menu.profileId)
            add("S_MODULE_ID", menu.moduleId)
            add("S_MENU_ID", menu.menuId)
            add("S_PGM_ID", menu.programId)
            add("S_DSCLASS", "biz.user.UserDS")
            add("S_DSMETHOD", "logProgram")
            add("S_FORWARD", "xsheetResultXML")
        }
        val response = postCommonAction(cookieJar, form)
        log.warn("JadeHR logProgram response. message={}, etcKeys={}", response.message, response.etcData.keys)
    }

    private fun loginForm(
        credential: JadeHrCredential,
        method: String,
    ): MultiValueMap<String, String> = LinkedMultiValueMap<String, String>().apply {
        add("S_NO_LOGIN_CHECK", "Y")
        add("directpage_id", "")
        add("firstpage_id", "")
        add("S_C_CD", credential.companyCode.ifBlank { properties.defaultCompanyCode })
        add("S_USER_ID", credential.userId)
        add("S_PWD", credential.password)
        add("S_PGM_OPEN_TIME", "")
        add("S_ENC_OTP_KEY", "")
        add("S_CSRF_SALT", "null")
        add("S_DSCLASS", "biz.sys.sy_main.Sy_main_page")
        add("S_DSMETHOD", method)
        add("S_FORWARD", "xsheetResultXML")
    }

    private fun calendarForm(
        page: AttendancePageMetadata,
        yearMonth: YearMonth,
    ): MultiValueMap<String, String> = LinkedMultiValueMap<String, String>().apply {
        set("S_DSCLASS", page.dsClass)
        set("S_DSMETHOD", page.calendarMethod)
        set("S_FORWARD", "xsheetResultXML")
        set("S_SEL_YM", "%04d%02d".format(yearMonth.year, yearMonth.monthValue))
        set("S_EMP_ID", page.employeeId)
        set("S_GBN", "")
        set("S_HTML_VSN", "F_TAM4_GET_CALENDAR_HTML_V03")
        set("S_PGM_OPEN_TIME", page.programOpenTime)
        set("S_ENC_OTP_KEY", page.encryptedOtpKey)
        set("S_CSRF_SALT", page.csrfSalt)
        set("S_PAGE_PROFILE_ID", page.pageProfileId)
        set("S_PAGE_MODULE_ID", page.pageModuleId)
        set("S_PAGE_MENU_ID", page.pageMenuId)
        set("S_PAGE_PGM_URL", page.pageProgramUrl)
        set("S_PAGE_POP_URL", page.pageProgramUrl)
        set("S_PAGE_PGM_ID", page.pageProgramId)
        set("S_PAGE_ENC_VAL", page.pageEncValue)
        page.loginInfo?.let { set("X_LOGIN_INFO", it) }
    }

    private fun menuActionForm(
        menu: AttendanceMenuMetadata,
        yearMonth: YearMonth,
    ): MultiValueMap<String, String> = LinkedMultiValueMap<String, String>().apply {
        set("X_PROFILE_ID", menu.profileId)
        set("X_MODULE_ID", menu.moduleId)
        set("X_MENU_ID", menu.menuId)
        set("X_PGM_ID", menu.programId)
        set("X_SQL_ID", "")
        set("X_PRS_ID", "")
        set("X_EMP_SCH_AUTH_CD", menu.employeeSearchAuthCode)
        set("X_MENU_NM", menu.menuName)
        set("X_ENC_VAL", menu.encValue)
        set("X_ENC_VAL2", menu.encValue2)
        set("X_PGM_URL", menu.programUrl)
        set("X_POP_URL", menu.programUrl)
        menu.loginInfo?.let { set("X_LOGIN_INFO", it) }
        set("GEN_YN", menu.generatedYn)
        set("S_SQL_ID", "")
        set("S_PRS_ID", "")
        set("S_PROFILE_ID", menu.profileId)
        set("S_GEN_YN_YN", menu.generatedYn)
        set("S_MAIN_PAGE_YN", "")
        set("S_TOP_FRAME_TYPE", "")
        set("MASKING_USE_YN", menu.maskingUseYn)
        set("PER_NO_LIMIT_YN", menu.personalNumberLimitYn)
        set("RSN_POP_USE_YN", menu.reasonPopupUseYn)
        set("ENC_MASKING_USE_YN", menu.encMaskingUseYn)
        set("ENC_PER_NO_LIMIT_YN", menu.encPersonalNumberLimitYn)
        set("ENC_RSN_POP_USE_YN", menu.encReasonPopupUseYn)
        set("ENC_T", menu.encT)
        set("EXCEL_FILE_PWD_YN", menu.excelFilePasswordYn)
        set("CONF_INPUT_USE_YN", menu.confirmInputUseYn)
        set("CLIPBOARD_YN", menu.clipboardYn)
        set("S_STD_YMD", yearMonth.atDay(1).toString().replace("-", ""))
        addViewState(this)
    }

    private fun attendanceRecordPopupForm(
        context: JadeHrMainContext,
        type: AttendanceRecordType,
        mainInfo: HomeMainInfo,
    ): MultiValueMap<String, String> = LinkedMultiValueMap<String, String>().apply {
        val today = LocalDate.now(SEOUL_ZONE).format(BASIC_DATE_FORMAT)
        val employeeId = mainInfo.emp("EMP_ID")
            ?: mainInfo.tmp("EMP_ID")
            ?: context.page.employeeId
            ?: ""
        val workYmd = mainInfo.tmp("WORK_YMD") ?: today
        val attendManage = mainInfo.tmp("ATTEND_MANAGE") ?: DEFAULT_ATTEND_MANAGE
        val workPlanType = mainInfo.tmp("WORK_PLAN_TYPE") ?: DEFAULT_WORK_PLAN_TYPE
        val ipApi = mainInfo.tmp("IP_CHECK_YN").orEmpty()
        val unplanToast = mainInfo.tmp("UNPLAN_CHK_TOAST").orEmpty()

        set("X_PROFILE_ID", context.menu.profileId)
        set("X_MODULE_ID", context.menu.moduleId)
        set("X_MENU_ID", context.menu.menuId)
        set("X_PGM_ID", context.menu.programId)
        set("X_PGM_URL", context.menu.programUrl)
        set("X_POP_URL", "/ess/tam4/ess_tam_402_p01.jsp")
        set("X_MENU_NM", if (type == AttendanceRecordType.START) "출근기록" else "퇴근기록")
        set("X_ENC_VAL", context.menu.encValue)
        context.menu.loginInfo?.let { set("X_LOGIN_INFO", it) }
        set("S_EMP_ID", employeeId)
        set("S_YMD", workYmd)
        set("S_GUBUN", if (type == AttendanceRecordType.START) "STA" else "END")
        when (type) {
            AttendanceRecordType.START -> {
                set("S_STD_YMD", mainInfo.tmp("I_IN_YMD").orEmpty())
                set("S_STD_TIME", mainInfo.tmp("I_IN_HM").orEmpty())
                set("S_C_IN_YMD", mainInfo.tmp("C_IN_YMD") ?: workYmd)
                set("S_C_IN_HM", mainInfo.tmp("C_IN_HM").orEmpty().replace("--:--", ""))
                set("S_UNPLAN_CHK_TOAST", unplanToast)
            }

            AttendanceRecordType.END -> {
                set("S_STD_YMD", mainInfo.tmp("I_OUT_YMD").orEmpty())
                set("S_STD_TIME", mainInfo.tmp("I_OUT_HM").orEmpty())
                set("S_C_OUT_YMD", mainInfo.tmp("C_OUT_YMD") ?: workYmd)
                set("S_C_OUT_HM", mainInfo.tmp("C_OUT_HM").orEmpty().replace("--:--", ""))
                set("S_UNPLAN_CHK_MSG", unplanToast)
            }
        }
        set("S_ATTEND_MANAGE", attendManage)
        set("S_IP_API", ipApi)
        set("S_WORK_PLAN_TYPE", workPlanType)
        addViewState(this)
    }

    private fun attendanceRecordForm(
        popup: AttendanceRecordPopupMetadata,
        type: AttendanceRecordType,
    ): MultiValueMap<String, String> = LinkedMultiValueMap<String, String>().apply {
        val nowDate = LocalDate.now(SEOUL_ZONE).format(BASIC_DATE_FORMAT)
        val nowTime = LocalTime.now(SEOUL_ZONE).format(HOUR_MINUTE_FORMAT)
        val displayDate = LocalDate.now(SEOUL_ZONE).format(DISPLAY_DATE_FORMAT)
        val displayTime = LocalTime.now(SEOUL_ZONE).format(DISPLAY_TIME_FORMAT)
        popup.formFields.forEach { (key, value) -> set(key, value) }
        set("S_DSCLASS", popup.dsClass)
        set("S_DSMETHOD", popup.recordMethod)
        set("S_FORWARD", "xsheetResultXML")
        set("S_GUBUN", if (type == AttendanceRecordType.START) "STA" else "END")
        if (getFirst("S_YMD").isNullOrBlank()) set("S_YMD", nowDate)
        if (getFirst("S_STD_YMD").isNullOrBlank()) set("S_STD_YMD", nowDate)
        if (getFirst("S_STD_TIME").isNullOrBlank()) set("S_STD_TIME", nowTime)
        remove("S_STD_HM")
        if (getFirst("F_STD_YMD").isNullOrBlank()) set("F_STD_YMD", displayDate)
        if (getFirst("F_STD_TIME").isNullOrBlank()) set("F_STD_TIME", displayTime)
        if (getFirst("S_ATTEND_MANAGE").isNullOrBlank()) set("S_ATTEND_MANAGE", DEFAULT_ATTEND_MANAGE)
        if (getFirst("S_WORK_PLAN_TYPE").isNullOrBlank()) set("S_WORK_PLAN_TYPE", DEFAULT_WORK_PLAN_TYPE)
        set("S_PGM_OPEN_TIME", popup.programOpenTime)
        set("S_ENC_OTP_KEY", popup.encryptedOtpKey)
        set("S_CSRF_SALT", popup.csrfSalt)
        set("S_PAGE_PROFILE_ID", popup.pageProfileId)
        set("S_PAGE_MODULE_ID", popup.pageModuleId)
        set("S_PAGE_MENU_ID", popup.pageMenuId)
        set("S_PAGE_PGM_URL", popup.pageProgramUrl)
        set("S_PAGE_POP_URL", popup.pagePopUrl)
        set("S_PAGE_PGM_ID", popup.pageProgramId)
        set("S_PAGE_ENC_VAL", popup.pageEncValue)
    }

    private fun addViewState(form: MultiValueMap<String, String>) {
        if (form.containsKey("__viewState")) {
            return
        }
        form.set("__viewState", form.toSingleValueMap().toJadeViewState())
    }

    private suspend fun WebClient.RequestHeadersSpec<*>.exchangeToBody(
        cookieJar: JadeHrCookieJar,
    ): String = exchangeToMono { response: ClientResponse ->
        cookieJar.update(response.cookies())
        log.debug("JadeHR HTTP response. status={}, cookies={}", response.statusCode(), response.cookies().keys)
        if (response.statusCode().isError) {
            response.bodyToMono(String::class.java)
                .defaultIfEmpty("")
                .flatMap { Mono.error(JadeHrLoginException("JadeHR request failed: ${response.statusCode()}")) }
        } else {
            response.bodyToMono(String::class.java)
        }
    }.awaitSingle()

    companion object {
        private val log = LoggerFactory.getLogger(JadeHrClient::class.java)
        private val SEOUL_ZONE = ZoneId.of("Asia/Seoul")
        private val BASIC_DATE_FORMAT = DateTimeFormatter.BASIC_ISO_DATE
        private val HOUR_MINUTE_FORMAT = DateTimeFormatter.ofPattern("HHmm")
        private val DISPLAY_DATE_FORMAT = DateTimeFormatter.ofPattern("yyyy.MM.dd")
        private val DISPLAY_TIME_FORMAT = DateTimeFormatter.ofPattern("HH:mm")
        private const val DEFAULT_ATTEND_MANAGE = "0040"
        private const val DEFAULT_WORK_PLAN_TYPE = "000"
    }
}

private fun Map<String, String>.registeredStartTime(): LocalTime? =
    listOfNotNull(
        this["C_IN_YMDHM"],
        this["ALERT_C_IN_YMDHM"],
        this["S_C_IN_HM"],
    ).firstNotNullOfOrNull { it.toLocalTimeOrNull() }

private fun String.toLocalTimeOrNull(): LocalTime? {
    val digits = filter(Char::isDigit)
    val hourMinute = when {
        digits.length >= 12 -> digits.takeLast(4)
        digits.length == 4 -> digits
        else -> return null
    }
    val hour = hourMinute.substring(0, 2).toIntOrNull() ?: return null
    val minute = hourMinute.substring(2, 4).toIntOrNull() ?: return null

    return runCatching { LocalTime.of(hour, minute) }.getOrNull()
}

private data class XSheetResponse(
    val message: String?,
    val etcData: Map<String, String>,
    val rawXml: String,
) {
    companion object {
        fun parse(xml: String): XSheetResponse {
            val document = Jsoup.parse(xml, "", Parser.xmlParser())
            val message = document.selectFirst("MESSAGE")?.text()?.trim()
            val etcData = document.select("ETC-DATA *")
                .mapNotNull { element ->
                    val key = element.attr("KEY")
                        .ifBlank { element.attr("key") }
                        .ifBlank { element.attr("NAME") }
                        .ifBlank { element.attr("name") }
                        .ifBlank { element.tagName() }
                    key.takeIf { it.isNotBlank() }?.let { it to element.wholeText().trim() }
                }
                .toMap()

            return XSheetResponse(message = message, etcData = etcData, rawXml = xml)
        }
    }
}

private data class HomeMainInfo(
    val tmpInfo: Map<String, String>,
    val empInfo: Map<String, String>,
) {
    fun tmp(key: String): String? = tmpInfo[key]?.takeIf { it.isNotBlank() }

    fun emp(key: String): String? = empInfo[key]?.takeIf { it.isNotBlank() }

    companion object {
        val EMPTY = HomeMainInfo(tmpInfo = emptyMap(), empInfo = emptyMap())

        fun from(response: XSheetResponse): HomeMainInfo {
            val document = Jsoup.parse(response.rawXml, "", Parser.xmlParser())
            return HomeMainInfo(
                tmpInfo = document.rowMap("tmpInfo"),
                empInfo = document.rowMap("empInfo"),
            )
        }

        private fun org.jsoup.nodes.Document.rowMap(dataKey: String): Map<String, String> {
            val containers = select("*").filter { element ->
                listOf("id", "ID", "name", "NAME", "key", "KEY", "dataKey", "DATAKEY").any {
                    element.attr(it).equals(dataKey, ignoreCase = true)
                }
            }

            val direct = containers
                .asSequence()
                .map { it.extractFieldMap() }
                .firstOrNull { it.isNotEmpty() }

            if (!direct.isNullOrEmpty()) {
                return direct
            }

            return select("*").asSequence()
                .filter { it.tagName().equals(dataKey, ignoreCase = true) }
                .map { it.extractFieldMap() }
                .firstOrNull { it.isNotEmpty() }
                .orEmpty()
        }

        private fun org.jsoup.nodes.Element.extractFieldMap(): Map<String, String> {
            val namedChildren = select("*").mapNotNull { child ->
                val key = child.attr("name")
                    .ifBlank { child.attr("NAME") }
                    .ifBlank { child.attr("key") }
                    .ifBlank { child.attr("KEY") }
                    .ifBlank { child.attr("col") }
                    .ifBlank { child.attr("COL") }
                    .ifBlank { child.attr("field") }
                    .ifBlank { child.attr("FIELD") }
                    .ifBlank {
                        child.tagName()
                            .takeIf { tag -> tag.any(Char::isLetter) && tag.uppercase() !in XML_CONTAINER_TAGS }
                            .orEmpty()
                    }
                val value = child.wholeText().trim().ifBlank { child.attr("value") }
                key.takeIf { it.isNotBlank() && value.isNotBlank() }?.let { it to value }
            }.toMap()

            if (namedChildren.isNotEmpty()) {
                return namedChildren
            }

            return emptyMap()
        }

        private val XML_CONTAINER_TAGS = setOf(
            "SHEET",
            "DATA",
            "TR",
            "TD",
            "ROW",
            "ETC-DATA",
            "ETC",
            "MESSAGE",
        )
    }
}

private data class JadeHrMainContext(
    val cookieJar: JadeHrCookieJar,
    val mainHtml: String,
    val page: MainPageMetadata,
    val menu: AttendanceMenuMetadata,
)

private data class MainPageMetadata(
    val dsClass: String,
    val employeeId: String?,
    val getMainInfoMethod: String?,
) {
    companion object {
        fun parse(html: String): MainPageMetadata {
            val document = Jsoup.parse(html)
            return MainPageMetadata(
                dsClass = document.requiredValue("S_DSCLASS"),
                employeeId = document.valueOrNull("emp_id_info")
                    ?: document.valueOrNull("S_EMP_ID")
                    ?: html.extractJavaScriptString("EMP_ID"),
                getMainInfoMethod = html.extractHunelOtpValue("getMainInfo"),
            )
        }

        private fun org.jsoup.nodes.Document.requiredValue(id: String): String =
            valueOrNull(id) ?: throw JadeHrParseException("JadeHR main field $id was not found.")

        private fun org.jsoup.nodes.Document.valueOrNull(id: String): String? =
            selectFirst("#$id")?.attr("value")?.takeIf { it.isNotBlank() }
    }
}

private data class AttendanceRecordPopupMetadata(
    val dsClass: String,
    val recordMethod: String,
    val validateMethod: String?,
    val programOpenTime: String,
    val encryptedOtpKey: String,
    val csrfSalt: String,
    val pageProfileId: String,
    val pageModuleId: String,
    val pageMenuId: String,
    val pageProgramUrl: String,
    val pagePopUrl: String,
    val pageProgramId: String,
    val pageEncValue: String,
    val formFields: Map<String, String>,
) {
    companion object {
        private val caseRegex = Regex("""case\s+"([^"]+)"\s*:""")
        private val validateMethodRegex = Regex(
            pattern = """ajaxSyncRequestXS\(\$\("#S_DSCLASS"\)\.val\(\),\s*"([^"]+)"\s*,\s*serializeForm\(\)""",
            options = setOf(RegexOption.DOT_MATCHES_ALL),
        )

        fun parse(html: String): AttendanceRecordPopupMetadata {
            if (html.contains("<title>::logout::</title>", ignoreCase = true)) {
                throw JadeHrSessionRequiredException("JadeHR session is expired. Please login again.")
            }

            val document = Jsoup.parse(html)
            val cases = caseRegex.findAll(html).map { it.groupValues[1] }.toList()
            val recordMethod = cases.firstOrNull()
                ?: throw JadeHrParseException("JadeHR attendance record method was not found.")

            return AttendanceRecordPopupMetadata(
                dsClass = document.requiredValue("S_DSCLASS"),
                recordMethod = recordMethod,
                validateMethod = validateMethodRegex.find(html)?.groupValues?.get(1),
                programOpenTime = html.extractJavaScriptString("S_PGM_OPEN_TIME") ?: "",
                encryptedOtpKey = html.extractJavaScriptString("S_ENC_OTP_KEY") ?: "",
                csrfSalt = html.extractJavaScriptString("S_CSRF_SALT") ?: "null",
                pageProfileId = document.valueOrNull("S_PAGE_PROFILE_ID")
                    ?: html.extractPageString("PROFILE_ID")
                    ?: "ESS",
                pageModuleId = document.valueOrNull("S_PAGE_MODULE_ID")
                    ?: html.extractPageString("MODULE_ID")
                    ?: "essMe",
                pageMenuId = document.valueOrNull("S_PAGE_MENU_ID")
                    ?: html.extractPageString("MENU_ID")
                    ?: "essmeV032",
                pageProgramUrl = document.valueOrNull("S_PAGE_PGM_URL")
                    ?: html.extractPageString("PGM_URL")
                    ?: "/ess/tam4/ess_tam_401_m02.jsp",
                pagePopUrl = document.valueOrNull("S_PAGE_POP_URL")
                    ?: html.extractPageString("POP_URL")
                    ?: "/ess/tam4/ess_tam_402_p01.jsp",
                pageProgramId = document.valueOrNull("S_PAGE_PGM_ID")
                    ?: html.extractPageString("PGM_ID")
                    ?: "ess_tam4_401_m02",
                pageEncValue = document.valueOrNull("S_PAGE_ENC_VAL")
                    ?: html.extractPageString("ENC_VAL")
                    ?: "5146AF559F19309361E003FB339E47779F90682293211A7102A9CD66D1586C6F",
                formFields = document.formFields(),
            )
        }

        private fun org.jsoup.nodes.Document.requiredValue(id: String): String =
            valueOrNull(id) ?: throw JadeHrParseException("JadeHR popup field $id was not found.")

        private fun org.jsoup.nodes.Document.valueOrNull(id: String): String? =
            selectFirst("#$id")?.attr("value")?.takeIf { it.isNotBlank() }

        private fun org.jsoup.nodes.Document.formFields(): Map<String, String> =
            select("input, select, textarea")
                .mapNotNull { element ->
                    val key = element.attr("name").ifBlank { element.id() }
                    key.takeIf { it.isNotBlank() }?.let {
                        val value = when (element.tagName().lowercase()) {
                            "textarea" -> element.wholeText()
                            "select" -> element.selectFirst("option[selected]")?.attr("value")
                                ?: element.selectFirst("option")?.attr("value")
                                ?: ""
                            else -> element.attr("value")
                        }
                        it to value
                    }
                }
                .toMap()
    }
}

private data class AttendancePageMetadata(
    val dsClass: String,
    val calendarMethod: String,
    val employeeId: String,
    val totalClass: String?,
    val pageProfileId: String,
    val pageModuleId: String,
    val pageMenuId: String,
    val pageProgramUrl: String,
    val pageProgramId: String,
    val pageEncValue: String,
    val programOpenTime: String,
    val encryptedOtpKey: String,
    val csrfSalt: String,
    val loginInfo: String?,
    val formFields: Map<String, String>,
    val methodCandidates: List<String>,
) {
    companion object {
        private val caseRegex = Regex(
            pattern = """case\s+"([^"]+)"\s*:""",
            options = setOf(RegexOption.DOT_MATCHES_ALL),
        )

        fun parse(
            html: String,
            menu: AttendanceMenuMetadata,
        ): AttendancePageMetadata {
            val document = Jsoup.parse(html)
            val calendarMethods = calendarMethodCandidates(html)
            val calendarMethod = calendarMethods.firstOrNull()
                ?: throw JadeHrParseException("JadeHR calendar method was not found.")

            return AttendancePageMetadata(
                dsClass = document.requiredValue("S_DSCLASS"),
                calendarMethod = calendarMethod,
                employeeId = document.requiredValue("S_EMP_ID"),
                totalClass = document.valueOrNull("S_TOT_CLASS"),
                pageProfileId = document.valueOrNull("S_PAGE_PROFILE_ID") ?: menu.profileId,
                pageModuleId = document.valueOrNull("S_PAGE_MODULE_ID") ?: menu.moduleId,
                pageMenuId = document.valueOrNull("S_PAGE_MENU_ID") ?: menu.menuId,
                pageProgramUrl = document.valueOrNull("S_PAGE_PGM_URL") ?: menu.programUrl,
                pageProgramId = document.valueOrNull("S_PAGE_PGM_ID") ?: menu.programId,
                pageEncValue = document.valueOrNull("S_PAGE_ENC_VAL") ?: menu.encValue,
                programOpenTime = document.valueOrNull("S_PGM_OPEN_TIME")
                    ?: html.extractJavaScriptString("S_PGM_OPEN_TIME")
                    ?: "",
                encryptedOtpKey = document.valueOrNull("S_ENC_OTP_KEY")
                    ?: html.extractJavaScriptString("S_ENC_OTP_KEY")
                    ?: "",
                csrfSalt = document.valueOrNull("S_CSRF_SALT")
                    ?: html.extractJavaScriptString("S_CSRF_SALT")
                    ?: "null",
                loginInfo = menu.loginInfo ?: html.extractJavaScriptString("_LOGIN_INFO"),
                formFields = document.formFields(),
                methodCandidates = calendarMethods,
            )
        }

        private fun calendarMethodCandidates(html: String): List<String> {
            val matches = caseRegex.findAll(html).toList()
            return matches.mapIndexedNotNull { index, match ->
                val blockStart = match.range.last + 1
                val blockEnd = matches.getOrNull(index + 1)?.range?.first ?: html.length
                val block = html.substring(blockStart, blockEnd)
                val method = match.groupValues[1]

                method.takeIf {
                    block.contains("CAL_HTML") &&
                        block.contains("S_HTML_VSN") &&
                        block.contains("F_TAM4_GET_CALENDAR_HTML_V03")
                }
            }
        }

        private fun org.jsoup.nodes.Document.requiredValue(id: String): String =
            valueOrNull(id) ?: throw JadeHrParseException("JadeHR field $id was not found.")

        private fun org.jsoup.nodes.Document.valueOrNull(id: String): String? =
            selectFirst("#$id")?.attr("value")?.takeIf { it.isNotBlank() }

        private fun org.jsoup.nodes.Document.formFields(): Map<String, String> =
            select("input, select, textarea")
                .mapNotNull { element ->
                    val key = element.attr("name").ifBlank { element.id() }
                    if (key.isBlank()) {
                        null
                    } else {
                        val value = when (element.tagName().lowercase()) {
                            "select" -> element.selectFirst("option[selected]")?.attr("value")
                                ?: element.selectFirst("option")?.attr("value")
                                ?: ""
                            "textarea" -> element.wholeText()
                            else -> element.attr("value")
                        }
                        key to value
                    }
                }
                .toMap()
    }
}

private data class AttendanceMenuMetadata(
    val profileId: String,
    val moduleId: String,
    val menuId: String,
    val menuName: String,
    val programUrl: String,
    val programId: String,
    val employeeSearchAuthCode: String,
    val generatedYn: String,
    val encValue: String,
    val encValue2: String,
    val maskingUseYn: String,
    val personalNumberLimitYn: String,
    val reasonPopupUseYn: String,
    val encMaskingUseYn: String,
    val encPersonalNumberLimitYn: String,
    val encReasonPopupUseYn: String,
    val encT: String,
    val excelFilePasswordYn: String,
    val confirmInputUseYn: String,
    val clipboardYn: String,
    val loginInfo: String?,
) {
    fun withLoginInfo(loginInfo: String?): AttendanceMenuMetadata =
        copy(loginInfo = loginInfo ?: this.loginInfo)

    companion object {
        private const val ATTENDANCE_MENU_ID = "essmeV032"

        fun parse(html: String): AttendanceMenuMetadata {
            val element = Jsoup.parse(html).selectFirst("""span[menu_id="$ATTENDANCE_MENU_ID"]""")
                ?: return DEFAULT.also {
                    LoggerFactory.getLogger(AttendanceMenuMetadata::class.java).warn(
                        "JadeHR attendance menu metadata was not found in main HTML. Use default menu metadata.",
                    )
                }

            return AttendanceMenuMetadata(
                profileId = element.requiredAttr("profile_id"),
                moduleId = element.requiredAttr("module_id"),
                menuId = element.requiredAttr("menu_id"),
                menuName = element.attrOrDefault("menu_nm", "근태현황"),
                programUrl = element.requiredAttr("pgm_url"),
                programId = element.requiredAttr("pgm_id"),
                employeeSearchAuthCode = element.attrOrDefault("emp_sch_auth_cd", "10"),
                generatedYn = element.attrOrDefault("gen_yn", "Y"),
                encValue = element.requiredAttr("enc_val"),
                encValue2 = element.attrOrDefault("enc_val2", element.requiredAttr("enc_val")),
                maskingUseYn = element.attrOrDefault("masking_use_yn", "N"),
                personalNumberLimitYn = element.attrOrDefault("per_no_limit_yn", "N"),
                reasonPopupUseYn = element.attrOrDefault("rsn_pop_use_yn", "N"),
                encMaskingUseYn = element.attrOrDefault("enc_masking_use_yn", ""),
                encPersonalNumberLimitYn = element.attrOrDefault("enc_per_no_limit_yn", ""),
                encReasonPopupUseYn = element.attrOrDefault("enc_rsn_pop_use_yn", ""),
                encT = element.attrOrDefault("enc_t", ""),
                excelFilePasswordYn = element.attrOrDefault("excel_file_pwd_yn", "N"),
                confirmInputUseYn = element.attrOrDefault("conf_input_use_yn", "N"),
                clipboardYn = element.attrOrDefault("clipboard_yn", ""),
                loginInfo = null,
            )
        }

        private val DEFAULT = AttendanceMenuMetadata(
            profileId = "ESS",
            moduleId = "essMe",
            menuId = "essmeV032",
            menuName = "근태현황",
            programUrl = "/ess/tam4/ess_tam_401_m02.jsp",
            programId = "ess_tam4_401_m02",
            employeeSearchAuthCode = "10",
            generatedYn = "Y",
            encValue = "5146AF559F19309361E003FB339E47779F90682293211A7102A9CD66D1586C6F",
            encValue2 = "5146AF559F19309361E003FB339E47779F90682293211A7102A9CD66D1586C6F",
            maskingUseYn = "N",
            personalNumberLimitYn = "N",
            reasonPopupUseYn = "N",
            encMaskingUseYn = "",
            encPersonalNumberLimitYn = "",
            encReasonPopupUseYn = "fU87TxZiEA22XRo7gwRaPDi6cLtKFSM58B6SDloA67wOU8gGs850EukGe9lZpqOR",
            encT = "",
            excelFilePasswordYn = "N",
            confirmInputUseYn = "N",
            clipboardYn = "",
            loginInfo = null,
        )

        private fun org.jsoup.nodes.Element.requiredAttr(name: String): String =
            attr(name).takeIf { it.isNotBlank() }
                ?: throw JadeHrParseException("JadeHR menu field $name was not found.")

        private fun org.jsoup.nodes.Element.attrOrDefault(
            name: String,
            defaultValue: String,
        ): String = attr(name).takeIf { it.isNotBlank() } ?: defaultValue
    }
}

private fun String.extractJavaScriptString(name: String): String? {
    val pattern = Regex("""(?:var\s+)?${Regex.escape(name)}\s*=\s*["']([^"']*)["']""")
    return pattern.find(this)?.groupValues?.get(1)?.takeIf { it.isNotBlank() }
}

private fun String.extractHunelOtpValue(name: String): String? {
    val pattern = Regex("""${Regex.escape(name)}\s*:\s*["']([^"']*)["']""")
    return pattern.find(this)?.groupValues?.get(1)?.takeIf { it.isNotBlank() }
}

private fun String.extractPageString(name: String): String? {
    val pattern = Regex("""${Regex.escape(name)}\s*:\s*["']([^"']*)["']""")
    return pattern.find(this)?.groupValues?.get(1)?.takeIf { it.isNotBlank() }
}

private fun Map<String, String>.toJadeViewState(): String {
    val order = StringBuilder()
    val hashSource = StringBuilder()

    forEach { (key, value) ->
        if (key != "__viewState" && key != "undefined" && key.shouldIncludeInViewState()) {
            order.append(key).append(',')
            hashSource.append(value.toJadeHashToken())
        }
    }

    val test = hashSource.toString()
    return """{"order":"$order", "hash":"${test.sha256Rounds(3)}", "test":"$test"}"""
}

private fun String.shouldIncludeInViewState(): Boolean =
    listOf("EMP_ID", "ORG_ID", "C_CD", "AUTH", "APPL").any { contains(it) }

private fun String.toJadeHashToken(): String {
    val compact = replace(Regex(""" |,|\s"""), "")
    return URLEncoder.encode(compact, Charsets.UTF_8)
        .replace("+", "%20")
        .replace(Regex("""\W"""), "")
        .replace("　", "")
}

private fun String.sha256Rounds(rounds: Int): String {
    val digest = MessageDigest.getInstance("SHA-256")
    var bytes = toByteArray(Charsets.UTF_8)
    repeat(rounds) {
        bytes = digest.digest(bytes)
        digest.reset()
    }
    return bytes.joinToString(separator = "") { "%02X".format(it) }
}
