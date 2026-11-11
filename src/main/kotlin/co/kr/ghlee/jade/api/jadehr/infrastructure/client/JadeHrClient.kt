package co.kr.ghlee.jade.api.jadehr.infrastructure.client

import co.kr.ghlee.jade.api.jadehr.infrastructure.config.JadeHrProperties
import co.kr.ghlee.jade.api.jadehr.domain.ApprovalLine
import co.kr.ghlee.jade.api.jadehr.domain.ApprovalLineMember
import co.kr.ghlee.jade.api.jadehr.domain.ApplicationSubmitResult
import co.kr.ghlee.jade.api.jadehr.domain.JadeHrApplicationStatus
import co.kr.ghlee.jade.api.jadehr.domain.WorkPlanRegisterResult
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

    /**
     * 신청서(재택·휴가·출퇴근 등록 요청 등)를 새로 열 때 JadeHR 이 주는 기본 결재선을 읽는다.
     * 브라우저 흐름과 같다: 신청 메뉴(essappl103)로 신청서 화면을 열고 → getInitInfo → getCourse(LineDetail).
     * 조회만 하며 저장하지 않는다.
     */
    suspend fun fetchApprovalLine(
        session: JadeHrSession,
        applType: String,
        applTypeName: String,
        formUrl: String,
    ): ApprovalLine = ApprovalLine(openApplication(session, applType, applTypeName, formUrl).members)

    /**
     * 재택 근무 신청서(1130)를 올린다. 브라우저의 「신청」 버튼과 같은 순서:
     * 원래 근무유형 조회 → 마감 체크(rtnMsg=OK) → 결재자 재직 확인 → apply01.
     * dryRun 이면 apply01 직전까지만 실행한다 (JadeHR 에 아무것도 남지 않음).
     */
    suspend fun submitRemoteWork(
        session: JadeHrSession,
        workDate: LocalDate,
        reason: String,
        approverEmpIds: List<String>,
        dryRun: Boolean,
    ): ApplicationSubmitResult {
        val ctx = openApplication(session, "1130", "재택 근무 신청서", "/tam4/tm_day/tm_day_170_m01.jsp")
        val f1 = ctx.form.f1Fields.toMutableMap().apply {
            putAll(ctx.initFields)
            put("S_TARGET_EMP_ID", ctx.form.loginEmpId)
            put("S_BEF_TARGET_EMP_ID", ctx.form.loginEmpId)
            put("S_TARGET_EMP_NM", ctx.form.loginEmpName)
            put("F_WORK_YMD", workDate.format(DISPLAY_DATE_FORMAT))
            put("S_WORK_YMD", workDate.format(BASIC_DATE_FORMAT))
            put("S_WORK_TYPE", REMOTE_WORK_TYPE)
            put("S_RSN", reason)
        }

        // 원래 근무유형 (날짜를 고르면 화면이 조회해 채우는 값)
        val orgCase = ctx.form.caseContaining("ORG_WORK_TYPE_TIME")
            ?: throw JadeHrParseException("재택 신청서에서 근무유형 조회 동작을 찾지 못했습니다.")
        val org = postCommonAction(ctx.cookieJar, ctx.form.formRequest(orgCase, f1))
        val orgWorkType = org.etcData["WORK_TYPE"].orEmpty()
        if (orgWorkType.isBlank()) {
            return ApplicationSubmitResult(false, "원래 근무유형을 확인하지 못했습니다. ${org.message.orEmpty()}".trim(), null)
        }
        if (orgWorkType == REMOTE_WORK_TYPE) {
            return ApplicationSubmitResult(false, "이미 재택근무일입니다.", null)
        }
        f1["S_ORG_WORK_TYPE"] = orgWorkType

        // 마감 체크
        val closeCase = ctx.form.caseContaining("rtnMsg")
            ?: throw JadeHrParseException("재택 신청서에서 마감 체크 동작을 찾지 못했습니다.")
        val close = postCommonAction(ctx.cookieJar, ctx.form.formRequest(closeCase, f1))
        val closeMsg = close.etcData["rtnMsg"].orEmpty()
        if (closeMsg != "OK") {
            return ApplicationSubmitResult(false, closeMsg.ifBlank { close.message ?: "마감 체크에 실패했습니다." }, null)
        }

        checkApprovers(ctx, approverEmpIds)?.let { return ApplicationSubmitResult(false, it, null) }

        if (dryRun) {
            return ApplicationSubmitResult(true, "시험 실행: 신청 직전까지 확인했습니다. (원래 근무유형 $orgWorkType)", null)
        }
        return apply(ctx, f1, approverEmpIds)
    }

    /**
     * 출퇴근시각 등록 요청서(1110)를 올린다. 브라우저 흐름과 같다:
     * 날짜 선택(근무유형·계획 조회) → 시각 입력(근태관리 유형·반올림 단위·최소 출근시각·조정 시각 조회)
     * → 신청 전 검사(기록 검사 ERRORCODE=0, 계획 대비 확인 PLAN_CHK_CONFIRM/CONFIRM) → 결재자 확인 → apply01.
     * 계산은 모두 JadeHR 서버가 하고 화면은 값만 옮긴다. dryRun 이면 apply01 직전까지만.
     */
    suspend fun submitAttendanceRequest(
        session: JadeHrSession,
        workDate: LocalDate,
        inOutClass: String,
        startTime: LocalTime?,
        endTime: LocalTime?,
        reason: String,
        approverEmpIds: List<String>,
        dryRun: Boolean,
    ): ApplicationSubmitResult {
        fun fail(msg: String) = ApplicationSubmitResult(false, msg, null)
        require(inOutClass in setOf("10", "20", "30")) { "출퇴근 구분이 올바르지 않습니다." }
        if (inOutClass != "20" && startTime == null) return fail("출근 시각을 입력하세요.")
        if (inOutClass != "10" && endTime == null) return fail("퇴근 시각을 입력하세요.")
        if (inOutClass == "30" && !endTime!!.isAfter(startTime)) return fail("퇴근 시각은 출근 시각보다 늦어야 합니다.")
        if (workDate.isAfter(LocalDate.now(SEOUL_ZONE))) {
            return fail("출퇴근시각 등록 요청은 오늘을 포함한 지난 근무일만 신청할 수 있습니다.")
        }

        val ctx = openApplication(session, "1110", "출퇴근시각 등록 요청서", "/tam4/tm_day/tm_day_080_m01.jsp")
        val js = ctx.form.scripts
        val ymd = workDate.format(BASIC_DATE_FORMAT)
        val empId = ctx.form.loginEmpId
        val f1 = ctx.form.f1Fields.toMutableMap().apply {
            putAll(ctx.initFields)
            put("S_TARGET_EMP_ID", empId)
            put("S_BEF_TARGET_EMP_ID", empId)
            put("S_TARGET_EMP_NM", ctx.form.loginEmpName)
            put("F_WORK_YMD", workDate.format(DISPLAY_DATE_FORMAT))
            put("S_WORK_YMD", ymd)
            put("S_IN_OUT_CLASS", inOutClass)
            put("S_STA_YMD_CLASS", "2")
            put("S_END_YMD_CLASS", "2")
            put("F_STA_HM", startTime?.format(DISPLAY_TIME_FORMAT).orEmpty())
            put("F_END_HM", endTime?.format(DISPLAY_TIME_FORMAT).orEmpty())
            put("S_STA_HM", startTime?.format(HOUR_MINUTE_FORMAT).orEmpty())
            put("S_END_HM", endTime?.format(HOUR_MINUTE_FORMAT).orEmpty())
            // setYmd(): 당일(2) 기준
            put("S_STA_YMD", ymd)
            put("S_END_YMD", ymd)
            put("S_RSN", reason)
        }

        // 1) 날짜 선택: 근무유형·계획 시각
        val dateSegment = JsSource.caseSegment(JsSource.functionBody(js, "change_date"), "F_WORK_YMD")
            ?: throw JadeHrParseException("출퇴근 요청서에서 날짜 변경 동작을 찾지 못했습니다.")
        val dateCaseKey = JsSource.doAction2Keys(dateSegment).firstOrNull()
            ?: throw JadeHrParseException("출퇴근 요청서에서 근무유형 조회 동작을 찾지 못했습니다.")
        val workTypeMethod = JsSource.literalCalls(ctx.form.caseBody(dateCaseKey).orEmpty())
            .firstOrNull { it.second.contains("WORK_TYPE_CD") }?.first
            ?: throw JadeHrParseException("출퇴근 요청서에서 근무유형 조회 메서드를 찾지 못했습니다.")
        val plan = postCommonAction(ctx.cookieJar, ctx.form.formRequest(workTypeMethod, f1)).etcData
        if (plan["PLAN_CHK_YN"].isNullOrBlank()) return fail("그날 근무 계획이 없어 신청할 수 없습니다.")
        if (plan["PLAN_CHK_YN"] == "Y" && (plan["P_IN_HM"].isNullOrBlank() || plan["P_OUT_HM"].isNullOrBlank())) {
            return fail("그날 계획 근무시각이 없어 신청할 수 없습니다.")
        }
        f1["S_WORK_TYPE"] = plan["WORK_TYPE"].orEmpty()
        f1["S_WORK_TYPE_CD"] = plan["WORK_TYPE_CD"].orEmpty()

        // 2) 시각 입력: 출근만이면 F_STA_HM, 아니면 F_END_HM 흐름 (출퇴근이면 마지막 입력 기준)
        val timeSegment = JsSource.caseSegment(JsSource.functionBody(js, "change_date"), if (inOutClass == "10") "F_STA_HM" else "F_END_HM")
            ?: throw JadeHrParseException("출퇴근 요청서에서 시각 변경 동작을 찾지 못했습니다.")
        val calls = JsSource.literalCalls(timeSegment)
        fun method(marker: String) = calls.firstOrNull { it.second.contains(marker) }?.first
            ?: throw JadeHrParseException("출퇴근 요청서에서 $marker 조회 메서드를 찾지 못했습니다.")

        val manage = postCommonAction(
            ctx.cookieJar,
            ctx.form.objectRequest(method("ATTEND_MANAGE"), mapOf("S_EMP_ID" to empId, "S_WORK_YMD" to ymd)),
        ).firstRow()
        val attendManage = manage["ATTEND_MANAGE"].orEmpty()
        f1["S_ATTEND_MANAGE"] = attendManage
        f1["S_MAIN_WORK_TYPE"] = manage["MAIN_WORK_TYPE_O"].orEmpty()
        var switchYn = if (attendManage in setOf("0040", "0050", "0060", "0070")) "Y" else "N"

        val calcPrefix = if (inOutClass == "10") "IN" else "OUT"
        val calc = postCommonAction(
            ctx.cookieJar,
            ctx.form.objectRequest(method("${calcPrefix}_CALC_ROUND"), mapOf("S_WORK_TYPE" to f1["S_MAIN_WORK_TYPE"].orEmpty())),
        ).firstRow()
        f1["S_${calcPrefix}_CALC_ROUND"] = calc["${calcPrefix}_CALC_ROUND"].orEmpty()
        f1["S_${calcPrefix}_CALC_UNIT"] = calc["${calcPrefix}_CALC_UNIT"].orEmpty()
        if (!calc["${calcPrefix}_CALC_UNIT"].isNullOrBlank()) {
            // 반올림 단위가 있는 근무유형은 화면이 시각을 보정한다 — 아직 재현하지 않아 막는다
            return fail("이 근무유형은 시각 보정 단위(${calc["${calcPrefix}_CALC_UNIT"]})가 있어 아직 자동 신청을 지원하지 않습니다.")
        }

        if (inOutClass == "10") {
            val minSta = postCommonAction(
                ctx.cookieJar,
                ctx.form.objectRequest(method("MIN_STA_HM"), mapOf("S_EMP_ID" to empId, "S_WORK_YMD" to ymd)),
            ).firstRow()["MIN_STA_HM"].orEmpty()
            f1["S_MIN_STA_HM"] = minSta
            if (minSta.isNotBlank() && f1["S_STA_HM"].orEmpty() < minSta) {
                return fail("${minSta.take(2)}:${minSta.drop(2)} 이후 출근시각을 입력하세요.")
            }
        }

        val adj = postCommonAction(
            ctx.cookieJar,
            ctx.form.objectRequest(
                method("ADJ_IN_HM"),
                mapOf(
                    "S_EMP_ID" to empId,
                    "S_WORK_YMD" to ymd,
                    "S_ATTEND_MANAGE" to attendManage,
                    "S_A_IN_HM" to f1["S_STA_HM"].orEmpty(),
                    "S_A_OUT_HM" to f1["S_END_HM"].orEmpty(),
                    "S_STA_YMD_CLASS" to "2",
                    "S_END_YMD_CLASS" to "2",
                ),
            ),
        ).firstRow()
        val adjIn = adj["ADJ_IN_HM"].orEmpty()
        val adjOut = adj["ADJ_OUT_HM"].orEmpty()
        when (inOutClass) {
            "30" -> {
                f1["S_ADJ_STA_YMD"] = adjIn.take(8); f1["S_ADJ_STA_HM"] = adjIn.drop(8).take(4)
                f1["S_ADJ_END_YMD"] = adjOut.take(8); f1["S_ADJ_END_HM"] = adjOut.drop(8).take(4)
            }
            "10" -> {
                f1["S_ADJ_STA_YMD"] = adjIn.take(8); f1["S_ADJ_STA_HM"] = adjIn.drop(8).take(4)
                f1["S_ADJ_END_YMD"] = adj["C_OUT_YMD"].orEmpty(); f1["S_ADJ_END_HM"] = adj["C_OUT_HM"].orEmpty()
            }
            else -> {
                f1["S_ADJ_STA_YMD"] = adj["C_IN_YMD"].orEmpty(); f1["S_ADJ_STA_HM"] = adj["C_IN_HM"].orEmpty()
                f1["S_ADJ_END_YMD"] = adjOut.take(8); f1["S_ADJ_END_HM"] = adjOut.drop(8).take(4)
            }
        }
        if (adj["P_IN_HM"].isNullOrBlank() && adj["P_OUT_HM"].isNullOrBlank()) switchYn = "N"
        f1["S_SWITCH_YN"] = switchYn
        log.info(
            "JadeHR attendance request prepared. date={}, io={}, adj={}~{} {}~{}, manage={}, workType={}",
            ymd, inOutClass, f1["S_ADJ_STA_YMD"], f1["S_ADJ_STA_HM"], f1["S_ADJ_END_YMD"], f1["S_ADJ_END_HM"],
            attendManage, f1["S_WORK_TYPE_CD"],
        )

        // 3) 신청 전 검사 (doValidation 이 부르는 doAction2 순서대로)
        val validationKeys = JsSource.doAction2Keys(JsSource.functionBody(js, "doValidation"))
        val chkKey = validationKeys.firstOrNull { ctx.form.caseBody(it).orEmpty().contains("ERRORCODE") }
            ?: throw JadeHrParseException("출퇴근 요청서에서 기록 검사 동작을 찾지 못했습니다.")
        val chk = postCommonAction(ctx.cookieJar, ctx.form.formRequest(chkKey, f1)).etcData
        if (chk["ERRORCODE"] != "0") return fail(chk["ERRORMESG"].orEmpty().ifBlank { "기록 검사를 통과하지 못했습니다." })

        val planKey = validationKeys.firstOrNull { ctx.form.caseBody(it).orEmpty().contains("PLAN_CHK_CONFIRM") }
            ?: throw JadeHrParseException("출퇴근 요청서에서 계획 대비 확인 동작을 찾지 못했습니다.")
        for (io in if (inOutClass == "30") listOf("10", "20") else listOf(inOutClass)) {
            val etc = postCommonAction(ctx.cookieJar, ctx.form.formRequest(planKey, f1 + ("S_IN_OUT_CLASS" to io))).etcData
            if (etc["PLAN_CHK_CONFIRM"] != "OK") return fail(etc["PLAN_CHK_CONFIRM"].orEmpty().ifBlank { "계획 대비 확인에 실패했습니다." })
            if (etc["CONFIRM"] != "OK") return fail(etc["CONFIRM"].orEmpty().ifBlank { "확인에 실패했습니다." })
        }

        checkApprovers(ctx, approverEmpIds)?.let { return fail(it) }
        if (dryRun) {
            return ApplicationSubmitResult(
                true,
                "시험 실행: 신청 직전까지 확인했습니다. (조정 ${f1["S_ADJ_STA_HM"]}~${f1["S_ADJ_END_HM"]}, 근무유형 ${f1["S_WORK_TYPE"]})",
                null,
            )
        }
        return apply(ctx, f1, approverEmpIds)
    }

    /**
     * 휴가 신청서(1020) — 지금은 종일 휴가만. 브라우저 흐름과 같다:
     * 근태 선택(규칙 조회) → 날짜 선택(그날 종일 시각·사용시간 조회) → 일수 계산
     * → 신청 전 검사(날짜별 사용 가능, 먼저 써야 할 휴가, 최종 확인 CHK_MSG) → 결재자 확인 → apply01.
     */
    suspend fun submitLeave(
        session: JadeHrSession,
        workDate: LocalDate,
        attendClass: String,
        /** null 이면 종일, 아니면 시작~종료 시각으로 부분 휴가 */
        partStart: LocalTime?,
        partEnd: LocalTime?,
        reason: String,
        approverEmpIds: List<String>,
        dryRun: Boolean,
    ): ApplicationSubmitResult {
        fun fail(msg: String) = ApplicationSubmitResult(false, msg, null)
        val ctx = openApplication(session, "1020", "휴가 신청서", "/tam4/tm_day/tm_day_010_m05.jsp")
        val js = ctx.form.scripts
        val ymd = workDate.format(BASIC_DATE_FORMAT)
        val empId = ctx.form.loginEmpId
        val f1 = ctx.form.f1Fields.toMutableMap().apply {
            putAll(ctx.initFields)
            put("S_TARGET_EMP_ID", empId)
            put("S_BEF_TARGET_EMP_ID", empId)
            put("S_TARGET_EMP_NM", ctx.form.loginEmpName)
            put("S_ATTEND_CLASS", attendClass)
            put("S_NOTE", reason)
        }

        // 1) 근태 선택: getRule
        val ruleCalls = JsSource.literalCalls(JsSource.functionBody(js, "getRule"))
        val quotaMethod = ruleCalls.firstOrNull { it.second.contains("QUOTA_TIME_DH") }?.first
            ?: throw JadeHrParseException("휴가 신청서에서 시간/일 단위 조회 메서드를 찾지 못했습니다.")
        val ruleMethod = ruleCalls.firstOrNull { it.second.contains("S_APPL_TYPE") }?.first
            ?: throw JadeHrParseException("휴가 신청서에서 근태 규칙 조회 메서드를 찾지 못했습니다.")
        val quotaDh = postCommonAction(ctx.cookieJar, ctx.form.formRequest(quotaMethod, f1)).firstRow()["QUOTA_TIME_DH"].orEmpty()
        f1["S_QUOTA_TIME_DH"] = quotaDh
        val ruleRes = postCommonAction(ctx.cookieJar, ctx.form.objectRequest(ruleMethod, mapOf("S_APPL_TYPE" to "1020")))
        val rule = ruleRes.allRows().firstOrNull { it["CD"] == attendClass }
            ?: return fail("이 계정에서 신청할 수 없는 휴가 종류입니다: $attendClass")
        f1["S_CD_Q_YN"] = rule["Q_YN"].orEmpty()
        f1["S_CD_Q_CD"] = rule["Q_CD"].orEmpty()
        f1["S_CD_D_CNT_TYPE"] = rule["D_CNT_TYPE"].orEmpty()
        f1["S_CD_USE_CNT"] = rule["USE_CNT"].orEmpty()
        rule["ATTEND_YN"]?.let { f1["S_CD_ATTEND_YN"] = it }
        rule["APPL_TIME_INPUT_YN"]?.let { f1["S_APPL_TIME_INPUT_YN"] = it }
        rule["APPL_USE_TIME_YN"]?.let { f1["S_APPL_USE_TIME_YN"] = it }

        // 2) 날짜: 종일 시각·사용시간
        f1["F_STA_YMD"] = workDate.format(DISPLAY_DATE_FORMAT)
        f1["F_END_YMD"] = workDate.format(DISPLAY_DATE_FORMAT)
        f1["S_STA_YMD"] = ymd
        f1["S_END_YMD"] = ymd
        // 날짜 목록 조회(createAttedTr 를 부르는 case) — 연차 신청 방식(APPL_ANL_USETYPE)을 준다
        ctx.form.cases.firstOrNull { it.second.contains("createAttedTr(xs, true, true)") }?.first?.let { listKey ->
            val list = postCommonAction(ctx.cookieJar, ctx.form.formRequest(listKey, f1)).firstRow()
            list["APPL_ANL_USETYPE"]?.let { f1["S_APPL_ANL_USETYPE"] = it }
        }
        if (f1["S_APPL_ANL_USETYPE"].isNullOrBlank()) return fail("연차 신청 방식(APPL_ANL_USETYPE)을 확인하지 못했습니다.")

        val allDay = partStart == null || partEnd == null
        val staHm: String
        val endHm: String
        val useHour: String
        val useMin: String
        val staClass: String
        val endClass: String
        if (allDay) {
            // 종일 체크 → 그날 종일 시각·사용시간
            val allDayMethod = JsSource.literalCalls(JsSource.functionBody(js, "attendYmdEventBinding"))
                .firstOrNull { it.second.contains("USE_HOUR") && it.second.contains("END_CLASS") }?.first
                ?: throw JadeHrParseException("휴가 신청서에서 종일 시각 조회 메서드를 찾지 못했습니다.")
            val day = postCommonAction(
                ctx.cookieJar,
                ctx.form.objectRequest(allDayMethod, mapOf("S_TARGET_EMP_ID" to empId, "S_ATTEND_YMD" to ymd, "S_ATTEND_CLASS" to attendClass)),
            ).etcData
            staHm = day["STA_HM"].orEmpty()
            endHm = day["END_HM"].orEmpty()
            useHour = day["USE_HOUR"].orEmpty()
            useMin = day["USE_MIN"].orEmpty().ifBlank { "00" }
            staClass = day["STA_CLASS"].orEmpty().ifBlank { "2" }
            endClass = day["END_CLASS"].orEmpty().ifBlank { "2" }
        } else {
            // 시각 입력(keyup) → 서버가 사용시간 계산 (S_STA_TIME/S_END_TIME)
            if (!partEnd!!.isAfter(partStart)) return fail("종료 시각은 시작 시각보다 늦어야 합니다.")
            val calcKey = ctx.form.cases.firstOrNull { it.second.contains("S_STA_TIME") }?.first
                ?: throw JadeHrParseException("휴가 신청서에서 사용시간 계산 동작을 찾지 못했습니다.")
            // 이 case 는 ajaxSyncRequestXS($("#S_DSCLASS").val(), $("#S_DSMETHOD").val(), param) — 메서드 = case 키
            val calcMethod = JsSource.literalCalls(ctx.form.caseBody(calcKey).orEmpty()).firstOrNull()?.first ?: calcKey
            staHm = partStart!!.format(HOUR_MINUTE_FORMAT)
            endHm = partEnd.format(HOUR_MINUTE_FORMAT)
            val calc = postCommonAction(
                ctx.cookieJar,
                ctx.form.objectRequest(
                    calcMethod,
                    mapOf(
                        "S_TARGET_EMP_ID" to empId, "S_STD_YMD" to ymd,
                        "S_STA_YMD" to ymd, "S_STA_TIME" to staHm,
                        "S_END_YMD" to ymd, "S_END_TIME" to endHm,
                    ),
                ),
            ).firstRow()
            useHour = calc["USE_HOUR"].orEmpty()
            useMin = calc["USE_MIN"].orEmpty().ifBlank { "00" }
            staClass = "2"
            endClass = "2"
            if (useHour.isBlank() || (useHour.toIntOrNull() == 0 && useMin.toIntOrNull() == 0)) {
                return fail("사용 시간이 0입니다. 시각을 확인하세요.")
            }
            // JadeHR 신청안내: 연차는 최소 2시간부터 1시간 단위 (시스템은 막지 않아 여기서 막는다)
            if (attendClass == ANNUAL_LEAVE && ((useHour.toIntOrNull() ?: 0) < 2 || (useMin.toIntOrNull() ?: 0) != 0)) {
                return fail("연차는 최소 2시간부터 1시간 단위로 사용할 수 있습니다. (계산된 사용 시간 ${useHour}시간 ${useMin}분)")
            }
        }
        if (staHm.length != 4 || endHm.length != 4 || useHour.isBlank()) {
            return fail("그날 근무 시각을 확인하지 못했습니다. (휴일이거나 근무 계획이 없을 수 있습니다)")
        }
        if (allDay) f1["S_ALL_DAY_YN"] = "on" else f1.remove("S_ALL_DAY_YN")
        f1.putAll(
            mapOf(
                // 날짜 행 (화면 입력칸)
                "S_ATTEND_YMD" to ymd,
                "S_STA_YMD_CLASS" to staClass,
                "F_STA_HM" to "${staHm.take(2)}:${staHm.drop(2)}",
                "S_END_YMD_CLASS" to endClass,
                "F_END_HM" to "${endHm.take(2)}:${endHm.drop(2)}",
                "F_USE_HOUR" to useHour,
                "F_USE_MIN" to useMin,
                "S_STA_HM_0" to staHm,
                "S_END_HM_0" to endHm,
                "S_USE_HOUR_0" to useHour,
                "S_USE_MIN_0" to useMin,
                // makeSaveParam()
                "S_ATTEND_YMDS" to ymd,
                "S_ALL_DAY_YNS" to if (allDay) "Y" else "N",
                "S_USE_HOURS" to useHour,
                "S_USE_MINS" to useMin,
                "S_STA_YMDS" to staClass,
                "S_STA_HMS" to staHm,
                "S_END_YMDS" to endClass,
                "S_END_HMS" to endHm,
                "S_UNIT_TIME_CDS" to "undefined",
            ),
        )
        // applSumTime(): 시간 단위면 합계 시간
        if (quotaDh == "H") {
            val sum = useHour.toDouble() + (useMin.toIntOrNull() ?: 0) / 60.0
            f1["S_APPL_SUM_TIME"] = if (sum % 1.0 == 0.0) sum.toInt().toString() else sum.toString()
        }

        // 3) 일수 계산 (신청 전 최종 확인 case 안의 T_D_CNT 호출)
        val finalKey = JsSource.doAction2Keys(JsSource.functionBody(js, "doApplyBefore")).firstOrNull()
            ?: throw JadeHrParseException("휴가 신청서에서 최종 확인 동작을 찾지 못했습니다.")
        val countMethod = JsSource.literalCalls(ctx.form.caseBody(finalKey).orEmpty())
            .firstOrNull { it.second.contains("T_D_CNT") }?.first
            ?: throw JadeHrParseException("휴가 신청서에서 일수 계산 메서드를 찾지 못했습니다.")
        val count = postCommonAction(ctx.cookieJar, ctx.form.formRequest(countMethod, f1)).firstRow()
        f1["S_DAYS_DUTIE_DAY"] = count["T_D_CNT"].orEmpty()
        f1["S_WORK_DUTIE_DAY"] = count["W_D_CNT"].orEmpty()
        if ((count["W_D_CNT"]?.toDoubleOrNull() ?: 0.0) == 0.0) return fail("신청 일수가 0일입니다. (휴일이거나 이미 휴가인 날)")

        // 4) 날짜별 사용 가능 확인 (checkAttendYmd)
        val ymdCheckCall = JsSource.classCalls(JsSource.functionBody(js, "checkAttendYmd"))
            .firstOrNull { it.third.contains("CHK_MSG") }
            ?: throw JadeHrParseException("휴가 신청서에서 날짜별 확인 메서드를 찾지 못했습니다.")
        val ymdCheck = postCommonAction(
            ctx.cookieJar,
            ctx.form.objectRequest(
                ymdCheckCall.second,
                mapOf(
                    "S_EMP_ID" to empId, "S_ATTEND_CD" to attendClass, "S_YMD" to ymd,
                    "S_APPL_HOUR" to useHour, "S_APPL_MIN" to useMin, "S_LANG_TYPE" to "ko",
                ),
            ).apply { set("S_DSCLASS", ymdCheckCall.first) },
        )
        val ymdMsg = ymdCheck.firstRow()["CHK_MSG"] ?: ymdCheck.etcData["CHK_MSG"]
        if (!ymdMsg.isNullOrBlank() && ymdMsg != "OK") return fail(ymdMsg)

        // 5) 먼저 써야 할 휴가 (ddct_check)
        val ddctMethod = JsSource.literalCalls(JsSource.functionBody(js, "ddct_check"))
            .firstOrNull { it.second.contains("APPLY_ATTEND_CD_NM") }?.first
            ?: throw JadeHrParseException("휴가 신청서에서 휴가 소진 순서 확인 메서드를 찾지 못했습니다.")
        val ddct = postCommonAction(ctx.cookieJar, ctx.form.formRequest(ddctMethod, f1)).firstRow()["APPLY_ATTEND_CD_NM"].orEmpty()
        if (ddct.isNotBlank()) return fail("${ddct}을(를) 먼저 소진해 주시기 바랍니다.")

        // 6) 최종 확인 (CHK_MSG=OK)
        val final = postCommonAction(ctx.cookieJar, ctx.form.formRequest(finalKey, f1)).etcData["CHK_MSG"].orEmpty()
        if (final != "OK") return fail(final.ifBlank { "휴가 신청 확인에 실패했습니다." })

        log.info(
            "JadeHR leave prepared. date={}, class={}, {}~{} {}h{}m, days={}/{}, quota={}",
            ymd, attendClass, staHm, endHm, useHour, useMin, f1["S_DAYS_DUTIE_DAY"], f1["S_WORK_DUTIE_DAY"], quotaDh,
        )
        checkApprovers(ctx, approverEmpIds)?.let { return fail(it) }
        if (dryRun) {
            return ApplicationSubmitResult(true, "시험 실행: 신청 직전까지 확인했습니다. (${staHm}~${endHm}, ${useHour}시간 ${useMin}분)", null)
        }
        return apply(ctx, f1, approverEmpIds)
    }

    /**
     * 신청내역(신청/결재 > 신청 > 신청내역) 목록에서 신청 번호별 진행 상태를 읽는다. 조회만 한다.
     * 상태 코드: 200 결재중 / 300 반려 / 900 최종완료. 삭제(회수)된 신청은 목록에 없다.
     */
    suspend fun fetchApplicationStatuses(
        session: JadeHrSession,
        from: LocalDate,
        to: LocalDate,
    ): Map<String, JadeHrApplicationStatus> {
        val cookieJar = JadeHrCookieJar().apply {
            session.cookies.forEach { (name, value) -> put(name, value) }
        }
        val mainHtml = webClient.get().uri("/").cookies(cookieJar::addTo).exchangeToBody(cookieJar)
        val menu = (AttendanceMenuMetadata.parse(mainHtml, APPL_MENU_ID) ?: AttendanceMenuMetadata.APPL_DEFAULT)
            .withLoginInfo(mainHtml.extractJavaScriptString("_LOGIN_INFO"))
        logProgram(cookieJar, menu)

        val listHtml = webClient.post()
            .uri("/menuAction.do")
            .header("Origin", properties.baseUrl)
            .header("Referer", "${properties.baseUrl}/")
            .contentType(MediaType.APPLICATION_FORM_URLENCODED)
            .cookies(cookieJar::addTo)
            .body(BodyInserters.fromFormData(applicationFormOpenForm(menu, "", "", APPL_LIST_URL)))
            .exchangeToBody(cookieJar)
        if (listHtml.contains("<title>::logout::</title>", ignoreCase = true)) {
            throw JadeHrSessionRequiredException("JadeHR session is expired. Please login again.")
        }
        val document = Jsoup.parse(listHtml)
        fun pageValue(key: String): String =
            Regex("(?<![A-Za-z0-9_])" + Regex.escape(key) + "\\s*:\\s*[\"']([^\"']*)[\"']").find(listHtml)?.groupValues?.get(1).orEmpty()
        val scripts = document.select("script").toList().filter { !it.hasAttr("src") }.joinToString("\n") { it.data() }
            .replace(Regex("/\\*[\\s\\S]*?\\*/"), "")
        val listMethod = JsSource.literalCalls(JsSource.functionBody(scripts, "drawApplList"))
            .firstOrNull { it.second.contains("S_TRG_EMP") }?.first
            ?: throw JadeHrParseException("신청내역 화면에서 목록 조회 메서드를 찾지 못했습니다.")
        val dsClass = document.selectFirst("#S_DSCLASS")?.attr("value")?.takeIf { it.isNotBlank() }
            ?: throw JadeHrParseException("신청내역 화면에서 S_DSCLASS 를 찾지 못했습니다.")
        // 목록 화면의 사번 칸은 스크립트가 채워 HTML 에는 비어 있다 — 메인 화면 정보(getMainInfo)에서 받는다
        val empId = listOf("S_EMP_ID", "S_LOGIN_EMP_ID", "S_TRG_EMP")
            .firstNotNullOfOrNull { id -> document.selectFirst("#$id")?.attr("value")?.takeIf { it.isNotBlank() } }
            ?: fetchHomeMainInfo(loadMainContext(session)).let { it.emp("EMP_ID") ?: it.tmp("EMP_ID") }
            ?: throw JadeHrParseException("신청내역 조회에 쓸 사번을 찾지 못했습니다.")

        val result = linkedMapOf<String, JadeHrApplicationStatus>()
        val pageSize = 100
        var start = 1
        while (true) {
            val form = LinkedMultiValueMap<String, String>().apply {
                set("S_EMP_ID", empId)
                set("S_EMP_SCH_AUTH_CD", "10")
                set("S_APPL_TYPE", "")
                set("S_STAT_CDS", "")
                set("S_TRG_EMP", empId)
                set("S_APPL_YMD_FR", from.format(BASIC_DATE_FORMAT))
                set("S_APPL_YMD_TO", to.format(BASIC_DATE_FORMAT))
                set("S_STARTPAGE", start.toString())
                set("S_APPL_TXT", "")
                set("S_NUM", pageSize.toString())
                set("S_PGM_OPEN_TIME", listHtml.extractJavaScriptString("S_PGM_OPEN_TIME").orEmpty())
                set("S_ENC_OTP_KEY", listHtml.extractJavaScriptString("S_ENC_OTP_KEY").orEmpty())
                set("S_CSRF_SALT", listHtml.extractJavaScriptString("S_CSRF_SALT").orEmpty())
                set("S_ENC_MASKING_USE_YN", pageValue("ENC_MASKING_USE_YN").ifBlank { "N" })
                set("S_ENC_PER_NO_LIMIT_YN", pageValue("ENC_PER_NO_LIMIT_YN").ifBlank { "N" })
                set("S_ENC_RSN_POP_USE_YN", pageValue("ENC_RSN_POP_USE_YN").ifBlank { "N" })
                set("S_ENC_T", pageValue("ENC_T"))
                set("S_DSCLASS", dsClass)
                set("S_DSMETHOD", listMethod)
                set("S_FORWARD", "xsheetResultXML")
                set("S_PAGE_PROFILE_ID", pageValue("PROFILE_ID"))
                set("S_PAGE_MODULE_ID", pageValue("MODULE_ID"))
                set("S_PAGE_MENU_ID", pageValue("MENU_ID"))
                set("S_PAGE_PGM_URL", pageValue("PGM_URL"))
                set("S_PAGE_POP_URL", pageValue("POP_URL"))
                set("S_PAGE_PGM_ID", pageValue("PGM_ID"))
                set("S_PAGE_ENC_VAL", pageValue("ENC_VAL"))
            }
            val res = postCommonAction(cookieJar, form)
            res.message?.takeIf { it.isNotBlank() }?.let { throw JadeHrParseException("신청내역 조회 실패: $it") }
            val rows = res.allRows()
            rows.forEach { row ->
                val applId = row["APPL_ID"].orEmpty()
                if (applId.isNotBlank()) {
                    result[applId] = JadeHrApplicationStatus(
                        applId = applId,
                        applType = row["APPL_TYPE"].orEmpty(),
                        statusCode = row["APPL_STAT_CD"].orEmpty(),
                        statusName = row["APPL_STAT_NM"].orEmpty(),
                    )
                }
            }
            val total = rows.firstOrNull()?.get("TOT_CNT")?.toIntOrNull() ?: rows.size
            start += pageSize
            if (rows.isEmpty() || start > total || start > 2000) break
        }
        log.info("Fetched JadeHR application statuses. from={}, to={}, count={}", from, to, result.size)
        return result
    }

    /**
     * 근태현황 > 일정등록 팝업(ess_tam_401_p02)으로 근무 계획 시각을 등록한다.
     * JadeHR 이 등록 가능으로 표시한 날(td planAbleYn=Y)만 모아 달마다 한 번에 저장한다.
     * dryRun 이면 등록 가능한 날만 찾고 저장하지 않는다.
     */
    suspend fun registerWorkPlan(
        session: JadeHrSession,
        startTime: LocalTime,
        endTime: LocalTime,
        months: List<YearMonth>,
        dryRun: Boolean,
    ): WorkPlanRegisterResult {
        val context = loadMainContext(session)
        val mainInfo = fetchHomeMainInfo(context)
        val empId = mainInfo.emp("EMP_ID") ?: mainInfo.tmp("EMP_ID") ?: context.page.employeeId
            ?: throw JadeHrParseException("일정 등록에 쓸 사번을 찾지 못했습니다.")

        // 세션 메뉴값(ENC_VAL)을 받아 쓴다 — 고정값으로는 팝업이 중간에 끊긴다
        val sessionMenu = fetchSessionMenus(context.cookieJar, context.mainHtml, context.menu.profileId, context.menu.moduleId)[context.menu.menuId]
        val menu = context.menu.withSessionMenu(sessionMenu)
        if (sessionMenu == null) log.warn("JadeHR session menu {} not found. Use fixed metadata.", context.menu.menuId)

        // 브라우저처럼 근태현황 화면(m02)을 먼저 연 뒤 팝업을 연다
        logProgram(context.cookieJar, context.menu)
        webClient.post()
            .uri("/menuAction.do")
            .header("Origin", properties.baseUrl)
            .header("Referer", "${properties.baseUrl}/")
            .contentType(MediaType.APPLICATION_FORM_URLENCODED)
            .cookies(context.cookieJar::addTo)
            .body(BodyInserters.fromFormData(menuActionForm(menu, YearMonth.now(SEOUL_ZONE))))
            .exchangeToBody(context.cookieJar)

        // 브라우저 ModalUtil.open 이 보내는 그대로
        val popupForm = LinkedMultiValueMap<String, String>().apply {
            set("S_EMP_ID", empId)
            set("X_C_CD", properties.defaultCompanyCode)
            set("X_PROFILE_ID", menu.profileId)
            set("X_MODULE_ID", menu.moduleId)
            set("X_MENU_ID", menu.menuId)
            set("X_PGM_ID", menu.programId)
            set("X_SQL_ID", "")
            set("X_EMP_SCH_AUTH_CD", menu.employeeSearchAuthCode)
            set("X_MENU_NM", "")
            set("X_PGM_URL", menu.programUrl)
            set("X_POP_URL", WORK_PLAN_POPUP_URL)
            set("X_ENC_VAL", menu.encValue)
            set("X_HELP_PGM_ID", "")
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
            addViewState(this)
        }
        val html = webClient.post()
            .uri("/menuAction.do")
            .header("Origin", properties.baseUrl)
            .header("Referer", "${properties.baseUrl}/menuAction.do")
            .contentType(MediaType.APPLICATION_FORM_URLENCODED)
            .cookies(context.cookieJar::addTo)
            .body(BodyInserters.fromFormData(popupForm))
            .exchangeToBody(context.cookieJar)
        if (html.contains("<title>::logout::</title>", ignoreCase = true)) {
            throw JadeHrSessionRequiredException("JadeHR session is expired. Please login again.")
        }

        val document = Jsoup.parse(html)
        val f1 = document.selectFirst("form#f1") ?: document.selectFirst("form[name=f1]") ?: document.selectFirst("form")
            ?: run {
                log.warn(
                    "JadeHR work plan popup unexpected. length={}, raw={}",
                    html.length,
                    listOf("<form", "S_WORK_REG_BTN", "<title", "S_DSCLASS", "logout", "alert(").joinToString { k -> "$k@${html.indexOf(k)}" } +
                        " | PGM=" + Regex("PGM_URL\\s*:\\s*\"([^\"]*)\"").find(html)?.groupValues?.get(1) +
                        " POP=" + Regex("POP_URL\\s*:\\s*\"([^\"]*)\"").find(html)?.groupValues?.get(1) +
                        " | TAIL: " + html.takeLast(700)
                            .replace(Regex("[A-Za-z0-9+/=]{40,}"), "<enc>").replace(Regex("\\s+"), " "),
                )
                throw JadeHrParseException("일정등록 화면에서 입력 폼을 찾지 못했습니다. (${document.title()})")
            }
        val fields = linkedMapOf<String, String>()
        f1.select("input[name], select[name], textarea[name]").forEach { el ->
            val name = el.attr("name")
            val value = when (el.tagName()) {
                "select" -> (el.selectFirst("option[selected]") ?: el.selectFirst("option"))?.attr("value").orEmpty()
                "textarea" -> el.wholeText()
                else -> {
                    val type = el.attr("type").lowercase()
                    if ((type == "checkbox" || type == "radio") && !el.hasAttr("checked")) return@forEach
                    if (type == "button" || type == "submit" || type == "file") return@forEach
                    el.attr("value")
                }
            }
            if (name != "__viewState") fields[name] = value
        }
        val dsClass = fields["S_DSCLASS"]?.takeIf { it.isNotBlank() }
            ?: throw JadeHrParseException("일정등록 화면에서 S_DSCLASS 를 찾지 못했습니다.")
        val scripts = document.select("script").toList().filter { !it.hasAttr("src") }.joinToString("\n") { it.data() }
            .replace(Regex("/\\*[\\s\\S]*?\\*/"), "")
        val doActionBody = JsSource.functionBody(scripts, "doAction")
        val cases = Regex("case\\s+\"([^\"]{20,})\"\\s*:").findAll(doActionBody).toList().let { ms ->
            ms.mapIndexed { i, m -> m.groupValues[1] to doActionBody.substring(m.range.last + 1, if (i + 1 < ms.size) ms[i + 1].range.first else doActionBody.length) }
        }
        val calendarKey = Regex("doAction\\(\\s*['\"]([^'\"]{20,})['\"]").findAll(JsSource.functionBody(scripts, "settingInit"))
            .map { it.groupValues[1] }
            .firstOrNull { key -> cases.firstOrNull { it.first == key }?.second?.contains("CAL_HTML") == true }
            ?: throw JadeHrParseException("일정등록 화면에서 달력 조회 동작을 찾지 못했습니다.")
        val registerKey = document.selectFirst("#S_WORK_REG_BTN")?.attr("onclick")
            ?.let { Regex("doAction\\(\\s*['\"]([^'\"]{20,})['\"]").find(it)?.groupValues?.get(1) }
            ?: throw JadeHrParseException("일정등록 화면에서 등록 버튼 동작을 찾지 못했습니다.")

        // ajaxSyncRequestXS 공통 파라미터: 페이지 식별값은 Page 객체(KEY : "값")에서 읽는다
        fun pageValue(key: String): String =
            Regex("(?<![A-Za-z0-9_])" + Regex.escape(key) + "\\s*:\\s*[\"']([^\"']*)[\"']").find(html)?.groupValues?.get(1).orEmpty()
        fun common(method: String) = LinkedMultiValueMap<String, String>().apply {
            set("S_PGM_OPEN_TIME", html.extractJavaScriptString("S_PGM_OPEN_TIME").orEmpty())
            set("S_ENC_OTP_KEY", html.extractJavaScriptString("S_ENC_OTP_KEY").orEmpty())
            set("S_CSRF_SALT", html.extractJavaScriptString("S_CSRF_SALT").orEmpty())
            set("S_ENC_MASKING_USE_YN", pageValue("ENC_MASKING_USE_YN").ifBlank { "N" })
            set("S_ENC_PER_NO_LIMIT_YN", pageValue("ENC_PER_NO_LIMIT_YN").ifBlank { "N" })
            set("S_ENC_RSN_POP_USE_YN", pageValue("ENC_RSN_POP_USE_YN").ifBlank { "N" })
            set("S_ENC_T", pageValue("ENC_T"))
            set("S_DSCLASS", dsClass)
            set("S_DSMETHOD", method)
            set("S_FORWARD", "xsheetResultXML")
            set("S_PAGE_PROFILE_ID", pageValue("PROFILE_ID"))
            set("S_PAGE_MODULE_ID", pageValue("MODULE_ID"))
            set("S_PAGE_MENU_ID", pageValue("MENU_ID"))
            set("S_PAGE_PGM_URL", pageValue("PGM_URL"))
            set("S_PAGE_POP_URL", pageValue("POP_URL").ifBlank { WORK_PLAN_POPUP_URL })
            set("S_PAGE_PGM_ID", pageValue("PGM_ID"))
            set("S_PAGE_ENC_VAL", pageValue("ENC_VAL"))
        }

        val registered = mutableListOf<LocalDate>()
        val messages = mutableListOf<String>()
        for (ym in months) {
            val ymText = "%04d%02d".format(ym.year, ym.monthValue)
            val cal = postCommonAction(
                context.cookieJar,
                LinkedMultiValueMap<String, String>().apply {
                    set("S_SEL_YM", ymText)
                    set("S_EMP_ID", empId)
                    set("S_GBN", "P")
                    set("S_HTML_VSN", "F_TAM4_GET_CALENDAR_HTML_V03")
                    common(calendarKey).forEach { (k, v) -> put(k, v) }
                },
            )
            val calHtml = cal.etcData["CAL_HTML"].orEmpty()
            log.info(
                "JadeHR work plan calendar. ym={}, message={}, etcKeys={}, calLength={}, tdCount={}, sample={}",
                ymText, cal.message, cal.etcData.keys, calHtml.length, Regex("<td").findAll(calHtml).count(),
                Regex("<td[^>]*ymd[^>]*>").find(calHtml)?.value?.take(300),
            )
            val able = Jsoup.parse("<table><tbody>$calHtml</tbody></table>").select("td[ymd]").toList()
                .filter { it.attr("planableyn").equals("Y", ignoreCase = true) || it.attr("planAbleYn") == "Y" }
                .mapNotNull { runCatching { LocalDate.parse(it.attr("ymd"), BASIC_DATE_FORMAT) }.getOrNull() }
                .filter { YearMonth.from(it) == ym }
                .distinct().sorted()
            if (able.isEmpty()) continue
            if (dryRun) {
                registered += able
                continue
            }
            val save = postCommonAction(
                context.cookieJar,
                LinkedMultiValueMap<String, String>().apply {
                    fields.forEach { (k, v) -> set(k, v) }
                    set("S_EMP_ID", empId)
                    set("S_TARGET_EMP_ID", empId)
                    set("S_SEL_YM", ymText)
                    set("S_SEL_YMDS", able.joinToString(",") { it.format(BASIC_DATE_FORMAT) })
                    set("S_STA_CLASS", "2")
                    set("F_STA_HM", startTime.format(DISPLAY_TIME_FORMAT))
                    set("S_STA_HM", startTime.format(HOUR_MINUTE_FORMAT))
                    set("S_END_CLASS", "2")
                    set("F_END_HM", endTime.format(DISPLAY_TIME_FORMAT))
                    set("S_END_HM", endTime.format(HOUR_MINUTE_FORMAT))
                    common(registerKey).forEach { (k, v) -> put(k, v) }
                },
            )
            val err = save.message?.takeIf { it.isNotBlank() } ?: save.etcData["ALERT_MSG"]?.takeIf { it.isNotBlank() }
            if (err != null) {
                messages += "${ym.monthValue}월 등록 실패: $err"
            } else {
                registered += able
            }
        }
        log.info("JadeHR work plan {}. {}~{}, dates={}, errors={}", if (dryRun) "dry-run" else "registered", startTime, endTime, registered, messages)
        return WorkPlanRegisterResult(success = messages.isEmpty(), dates = registered, message = messages.joinToString(" / "))
    }

    /**
     * 로그인 세션의 메뉴 목록(왼쪽 메뉴)을 받아 메뉴 ID별 속성을 돌려준다.
     * 메뉴의 ENC_VAL 은 세션마다 달라, 이 값이 맞지 않으면 일부 화면(일정등록 팝업 등)이 중간에 끊긴다.
     */
    private suspend fun fetchSessionMenus(
        cookieJar: JadeHrCookieJar,
        mainHtml: String,
        profileId: String,
        moduleId: String,
    ): Map<String, Map<String, String>> {
        val menuDs = mainHtml.extractHunelOtpValue("MenuDS") ?: return emptyMap()
        val method = Regex("ajaxRequestJsonProg\\(\\s*'([^']+)'\\s*,\\s*'([^']+)'\\s*,\\s*\\{\\s*S_PROFILE_ID")
            .find(mainHtml)?.groupValues?.get(2) ?: return emptyMap()
        val form = LinkedMultiValueMap<String, String>().apply {
            set("S_PROFILE_ID", profileId)
            set("S_MODULE_ID", moduleId)
            set("S_DSCLASS", menuDs)
            set("S_DSMETHOD", method)
            set("S_FORWARD", "xsheetResultJson")
            set("S_PGM_OPEN_TIME", mainHtml.extractJavaScriptString("S_PGM_OPEN_TIME").orEmpty())
            set("S_ENC_OTP_KEY", mainHtml.extractJavaScriptString("S_ENC_OTP_KEY").orEmpty())
            set("S_CSRF_SALT", mainHtml.extractJavaScriptString("S_CSRF_SALT").orEmpty())
            set("S_ENC_MASKING_USE_YN", "N")
            set("S_ENC_PER_NO_LIMIT_YN", "N")
            set("S_ENC_RSN_POP_USE_YN", "N")
            set("S_ENC_T", "")
            set("S_PAGE_PROFILE_ID", "")
            set("S_PAGE_MODULE_ID", "")
            set("S_PAGE_MENU_ID", "")
            set("S_PAGE_PGM_URL", "/main/jsp/hunel3.jsp")
            set("S_PAGE_POP_URL", "/main/jsp/hunel3.jsp")
            set("S_PAGE_PGM_ID", "hunel")
            set("S_PAGE_ENC_VAL", "")
        }
        addViewState(form)
        val body = webClient.post()
            .uri("/commonAction.do")
            .header("ajax", "true")
            .header("X-Requested-With", "XMLHttpRequest")
            .header("Origin", properties.baseUrl)
            .header("Referer", "${properties.baseUrl}/")
            .contentType(MediaType.APPLICATION_FORM_URLENCODED)
            .cookies(cookieJar::addTo)
            .body(BodyInserters.fromFormData(form))
            .exchangeToMono { response ->
                cookieJar.update(response.cookies())
                response.bodyToMono(String::class.java)
            }
            .awaitSingle()
        return runCatching {
            val data = MENU_JSON.readTree(body.trim()).path("Data")
            data.mapNotNull { node ->
                val id = node.path("MENU_ID").asText("")
                if (id.isBlank()) null else id to node.fields().asSequence().associate { (k, v) -> k to v.asText("") }
            }.toMap()
        }.getOrElse {
            log.warn("JadeHR session menu parse failed. profile={}, module={}, message={}", profileId, moduleId, it.message)
            emptyMap()
        }
    }

    /** 고정 메뉴값에 세션 메뉴값(ENC_VAL 등)을 덮어쓴다 */
    private fun AttendanceMenuMetadata.withSessionMenu(menu: Map<String, String>?): AttendanceMenuMetadata {
        if (menu == null) return this
        fun v(key: String, default: String) = menu[key]?.takeIf { it.isNotBlank() } ?: default
        return copy(
            encValue = v("ENC_VAL", encValue),
            encValue2 = v("ENC_VAL2", v("ENC_VAL", encValue2)),
            encReasonPopupUseYn = menu["ENC_RSN_POP_USE_YN"] ?: encReasonPopupUseYn,
            encMaskingUseYn = menu["ENC_MASKING_USE_YN"] ?: encMaskingUseYn,
            encPersonalNumberLimitYn = menu["ENC_PER_NO_LIMIT_YN"] ?: encPersonalNumberLimitYn,
            encT = menu["ENC_T"] ?: encT,
        )
    }

    /** 결재자 재직 확인 (searchRetiree → RET_STAT=Y). 문제가 있으면 안내 문구 */
    private suspend fun checkApprovers(ctx: ApplicationContext, approverEmpIds: List<String>): String? {
        if (approverEmpIds.isEmpty()) return "결재자가 없습니다."
        val known = ctx.members.associateBy { it.empId }
        for (empId in approverEmpIds) {
            if (empId !in known) return "기본 결재선에 없는 결재자입니다: $empId"
            val res = postCommonAction(
                ctx.cookieJar,
                ctx.form.commonForm(ctx.form.searchRetireeMethod).apply { set("S_EMP_ID", empId) },
            )
            if (res.etcData["RET_STAT"] != "Y") {
                return "결재자 ${known[empId]?.name ?: empId} 을(를) 결재선에 넣을 수 없습니다. ${res.message.orEmpty()}".trim()
            }
        }
        return null
    }

    private suspend fun apply(
        ctx: ApplicationContext,
        f1: Map<String, String>,
        approverEmpIds: List<String>,
    ): ApplicationSubmitResult {
        val form = LinkedMultiValueMap<String, String>().apply {
            set("S_TEMP_APPR", "DEFAULT")
            set("S_APPR_EMP_IDS", approverEmpIds.joinToString(","))
            set("S_REFER_EMP_IDS", "")
            set("S_OLD_APPL_ID", "")
            set("S_PROC_FLAG", ctx.procFlag)
            set("S_INF_SEND_APPL", ctx.infSendAppl)
            set("S_APPL_TX_TOKEN", ctx.txToken)
            // 화면 값(serializeForm) — 위 결재 파라미터와 겹치는 키는 덮어쓰지 않는다
            f1.forEach { (k, v) -> if (!containsKey(k)) set(k, v) }
            ctx.form.commonForm(ctx.form.apply01Method).forEach { (k, v) -> put(k, v) }
            set("S_DSCLASS", ctx.form.formDsClass)
        }
        if (form.getFirst("S_PROC_FLAG").isNullOrBlank()) {
            return ApplicationSubmitResult(false, "신청 처리 구분(S_PROC_FLAG)을 확인하지 못해 신청하지 않았습니다.", null)
        }
        val res = postCommonAction(ctx.cookieJar, form)
        val applId = res.etcData["APPL_ID"]?.takeIf { it.isNotBlank() }
        val alert = res.etcData["ALERT_MSG"]?.takeIf { it.isNotBlank() }
        val message = res.message?.takeIf { it.isNotBlank() } ?: alert
        log.info("JadeHR apply01 result. applId={}, message={}, etcKeys={}", applId, message, res.etcData.keys)
        return if (message == null) {
            ApplicationSubmitResult(true, "신청했습니다.", applId)
        } else {
            ApplicationSubmitResult(false, message, applId)
        }
    }

    /** 신청서 화면을 열고 초기 정보(토큰)와 기본 결재선을 받는다 */
    private suspend fun openApplication(
        session: JadeHrSession,
        applType: String,
        applTypeName: String,
        formUrl: String,
    ): ApplicationContext {
        val cookieJar = JadeHrCookieJar().apply {
            session.cookies.forEach { (name, value) -> put(name, value) }
        }
        val mainHtml = webClient.get()
            .uri("/")
            .cookies(cookieJar::addTo)
            .exchangeToBody(cookieJar)
        // 메뉴 목록은 화면이 뜬 뒤 따로 불러와 메인 HTML 에 없는 경우가 많다 — 그때는 고정값을 쓴다 (출퇴근 메뉴와 같은 방식)
        val menu = (AttendanceMenuMetadata.parse(mainHtml, APPL_MENU_ID) ?: AttendanceMenuMetadata.APPL_DEFAULT)
            .withLoginInfo(mainHtml.extractJavaScriptString("_LOGIN_INFO"))

        logProgram(cookieJar, menu)

        val formHtml = webClient.post()
            .uri("/menuAction.do")
            .header("Origin", properties.baseUrl)
            .header("Referer", "${properties.baseUrl}/")
            .contentType(MediaType.APPLICATION_FORM_URLENCODED)
            .cookies(cookieJar::addTo)
            .body(BodyInserters.fromFormData(applicationFormOpenForm(menu, applType, applTypeName, formUrl)))
            .exchangeToBody(cookieJar)
        if (formHtml.contains("<title>::logout::</title>", ignoreCase = true)) {
            throw JadeHrSessionRequiredException("JadeHR session is expired. Please login again.")
        }
        val form = ApplicationFormMetadata.parse(formHtml)

        val initInfo = postCommonAction(
            cookieJar,
            form.commonForm(form.getInitInfoMethod).apply {
                set("S_APPL_TYPE", applType)
                set("S_APPL_ID", "")
            },
        )
        initInfo.message?.takeIf { it.isNotBlank() }?.let { throw JadeHrParseException("JadeHR getInitInfo 실패: $it") }
        val applTypeRow = initInfo.rows("ApplType").firstOrNull().orEmpty()
        val lineStand = applTypeRow["APPR_LINE_STAND"].orEmpty()

        val course = postCommonAction(
            cookieJar,
            form.commonForm(form.getCourseMethod).apply {
                set("S_COURSE", "TEMP")
                set("S_APPL_MODE", "APPL")
                set("S_APPL_TYPE", applType)
                set("S_APPL_ID", "")
                set("S_APPL_STAT_CD", "0")
                set("S_EMP_ID", "")
                set("S_TRG_EMP_ID", form.loginEmpId)
                set("S_APPR_LINE_STAND", lineStand)
            },
        )
        course.message?.takeIf { it.isNotBlank() }?.let { throw JadeHrParseException("JadeHR getCourse 실패: $it") }

        val members = course.rows("LineDetail").mapNotNull { row ->
            val empId = row["COL3"].orEmpty()
            if (empId.isBlank()) return@mapNotNull null
            ApprovalLineMember(
                order = row["COL1"]?.toIntOrNull() ?: 0,
                empId = empId,
                name = row["COL4"].orEmpty(),
                duty = row["COL5"].orEmpty(),
                apprType = row["COL10"].orEmpty(),
            )
        }.sortedBy { it.order }
        log.info(
            "Resolved JadeHR approval line. applType={}, members={}",
            applType,
            members.map { "${it.name}(${it.empId},${it.apprType})" },
        )

        return ApplicationContext(
            cookieJar = cookieJar,
            form = form,
            txToken = initInfo.etcData["S_APPL_TX_TOKEN"].orEmpty(),
            procFlag = applTypeRow["PROC_FLAG"]?.takeIf { it.isNotBlank() } ?: "10",
            infSendAppl = applTypeRow["INF_SEND_APPL"]?.takeIf { it.isNotBlank() } ?: "N",
            members = members,
            // 브라우저가 getInitInfo 응답으로 화면 hidden 값을 채우는 것과 같게 (inputSetValueAuto)
            initFields = INIT_FIELD_DEFAULTS.mapValues { (key, default) ->
                applTypeRow[key.removePrefix("S_")]?.takeIf { it.isNotBlank() } ?: default
            } + mapOf("S_APPL_TYPE_NM" to (applTypeRow["APPL_TYPE_NM"]?.takeIf { it.isNotBlank() } ?: applTypeName)),
        )
    }

    private fun applicationFormOpenForm(
        menu: AttendanceMenuMetadata,
        applType: String,
        applTypeName: String,
        formUrl: String,
    ): MultiValueMap<String, String> = LinkedMultiValueMap<String, String>().apply {
        set("S_TITEL_NM", "신청서작성")
        set("S_APPL_TYPE", applType)
        set("S_APPL_ID", "")
        set("S_APPL_MODE", "APPL")
        set("S_APPL_TYPE_NM", applTypeName)
        set("S_APPL_VERSION", "APPL_V03")
        set("S_LIST_SRC", "/sys/sy_appl/sy_appl_200_f01.jsp")
        set("X_C_CD", properties.defaultCompanyCode)
        set("X_PROFILE_ID", menu.profileId)
        set("X_MODULE_ID", menu.moduleId)
        set("X_MENU_ID", menu.menuId)
        set("X_PGM_ID", menu.programId)
        set("X_SQL_ID", "")
        set("X_EMP_SCH_AUTH_CD", menu.employeeSearchAuthCode)
        set("X_MENU_NM", "")
        set("X_PGM_URL", menu.programUrl)
        set("X_ENC_VAL", menu.encValue)
        set("X_POP_URL", formUrl)
        set("X_HELP_PGM_ID", "")
        menu.loginInfo?.let { set("X_LOGIN_INFO", it) }
        set("ENC_MASKING_USE_YN", menu.encMaskingUseYn)
        set("ENC_PER_NO_LIMIT_YN", menu.encPersonalNumberLimitYn)
        set("ENC_RSN_POP_USE_YN", menu.encReasonPopupUseYn)
        set("ENC_T", menu.encT)
        set("EXCEL_FILE_PWD_YN", menu.excelFilePasswordYn)
        set("CONF_INPUT_USE_YN", menu.confirmInputUseYn)
        set("CLIPBOARD_YN", menu.clipboardYn)
        addViewState(this)
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
            // 2026-08 JadeHR 변경: OTP·페이지 파라미터 없으면 getMainInfo 가 "알 수 없는 오류"를 돌려준다
            set("S_PGM_OPEN_TIME", context.page.programOpenTime.orEmpty())
            set("S_ENC_OTP_KEY", context.page.encryptedOtpKey.orEmpty())
            set("S_CSRF_SALT", context.page.csrfSalt.orEmpty())
            set("S_PAGE_PROFILE_ID", "")
            set("S_PAGE_MODULE_ID", "")
            set("S_PAGE_MENU_ID", "")
            set("S_PAGE_PGM_URL", context.page.pageProgramUrl.orEmpty())
            set("S_PAGE_ENC_VAL", "")
            set("S_PAGE_PGM_ID", context.page.pageProgramId.orEmpty())
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
            form.toSingleValueMap().mapValues { (k, v) -> if (k == "S_PWD") "****" else v },
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

        /** JadeHR 신청/결재 > 신청 메뉴 */
        private const val APPL_MENU_ID = "essappl103"

        private val MENU_JSON = com.fasterxml.jackson.databind.ObjectMapper()

        /** 근태현황 > 일정등록 팝업 */
        private const val WORK_PLAN_POPUP_URL = "/ess/tam4/ess_tam_401_p02.jsp"

        /** 신청 > 신청내역 목록 화면 */
        private const val APPL_LIST_URL = "/sys/sy_appl/sy_appl_200_f03.jsp"

        /** getInitInfo(ApplType) 로 채우는 신청서 hidden 값과 브라우저 기본값 */
        private val INIT_FIELD_DEFAULTS = linkedMapOf(
            "S_INF_USE_YN" to "N",
            "S_BTN_CHG_YN" to "N",
            "S_PROC_FLAG" to "10",
            "S_PROC_TYPE" to "10",
            "S_AUTO_CHG_NO" to "N",
            "S_AUTO_COMPLE_NO" to "N",
            "S_ONLY_APPL_TYPE_APPR_YN" to "N",
            "S_APPL_INSERT_REFER_YN" to "N",
            "S_APPR_INSERT_REFER_YN" to "N",
            "S_APPR_DELETE_REFER_YN" to "N",
            "S_APPR_LINE_EDIT_YN" to "Y",
            "S_DEFAULT_LINE_USE_ONLY_YN" to "",
        )

        /** 연차휴가 근태 코드 */
        private const val ANNUAL_LEAVE = "4010"

        /** 재택근무제 근무유형 코드 */
        private const val REMOTE_WORK_TYPE = "2040"
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

    /** 첫 DATA 의 모든 행 */
    fun allRows(): List<Map<String, String>> {
        val document = Jsoup.parse(rawXml, "", Parser.xmlParser())
        val key = document.select("DATA").firstOrNull()?.attr("KEY") ?: return emptyList()
        return rows(key)
    }

    /** 첫 DATA 의 첫 행 (xs.GetCellValue(0, ...) 와 같음) */
    fun firstRow(): Map<String, String> {
        val document = Jsoup.parse(rawXml, "", Parser.xmlParser())
        val key = document.select("DATA").firstOrNull()?.attr("KEY") ?: return emptyMap()
        return rows(key).firstOrNull().orEmpty()
    }

    /** xsheet DATA(KEY=dataKey) 의 모든 행: <HTR> 의 TD 가 컬럼명, 각 <TR> 의 TD 가 값 */
    fun rows(dataKey: String): List<Map<String, String>> {
        val document = Jsoup.parse(rawXml, "", Parser.xmlParser())
        val data = document.select("DATA").firstOrNull { it.attr("KEY").equals(dataKey, ignoreCase = true) }
            ?: return emptyList()
        val headers = data.children().firstOrNull { it.tagName().equals("HTR", ignoreCase = true) }
            ?.children()?.map { it.wholeText().trim() }
            ?: return emptyList()
        return data.children().toList()
            .filter { it.tagName().equals("TR", ignoreCase = true) }
            .map { tr ->
                headers.zip(tr.children().map { it.wholeText().trim() })
                    .filter { it.first.isNotBlank() }
                    .toMap()
            }
    }
}

/** 신청서 화면(menuAction.do 로 연 sy_appl_200_m03 + 신청서 jsp) 에서 꺼낸 호출 정보 */
private data class ApplicationFormMetadata(
    val programOpenTime: String,
    val encryptedOtpKey: String,
    val csrfSalt: String,
    val sysApplCommon: String,
    val getInitInfoMethod: String,
    val getCourseMethod: String,
    val searchRetireeMethod: String,
    val apply01Method: String,
    val loginEmpId: String,
    val loginEmpName: String,
    /** 신청서 고유 클래스 (form f1 의 S_DSCLASS) */
    val formDsClass: String,
    /** serializeForm("f1") 기본값 */
    val f1Fields: Map<String, String>,
    /** 신청서 화면 doAction2 의 case (암호화된 메서드 값, 블록 내용) */
    val cases: List<Pair<String, String>>,
    /** 신청서 화면 인라인 스크립트 전체 */
    val scripts: String,
    val page: Map<String, String>,
) {
    /** 블록에 marker 가 들어 있는 첫 case 의 메서드 값 */
    fun caseContaining(marker: String): String? = cases.firstOrNull { it.second.contains(marker) }?.first

    fun caseBody(key: String): String? = cases.firstOrNull { it.first == key }?.second

    /** 신청서 고유 동작을 객체 파라미터로 호출: ajaxSyncRequestXS($("#S_DSCLASS").val(), method, {..}) */
    fun objectRequest(method: String, params: Map<String, String>): LinkedMultiValueMap<String, String> =
        LinkedMultiValueMap<String, String>().apply {
            params.forEach { (k, v) -> set(k, v) }
            commonForm(method).forEach { (k, v) -> put(k, v) }
            set("S_DSCLASS", formDsClass)
        }

    /** ajaxSyncRequestXS 가 붙이는 공통 파라미터 */
    fun commonForm(method: String): LinkedMultiValueMap<String, String> = LinkedMultiValueMap<String, String>().apply {
        set("S_PGM_OPEN_TIME", programOpenTime)
        set("S_ENC_OTP_KEY", encryptedOtpKey)
        set("S_CSRF_SALT", csrfSalt)
        set("S_ENC_MASKING_USE_YN", page["ENC_MASKING_USE_YN"]?.ifBlank { null } ?: "N")
        set("S_ENC_PER_NO_LIMIT_YN", page["ENC_PER_NO_LIMIT_YN"]?.ifBlank { null } ?: "N")
        set("S_ENC_RSN_POP_USE_YN", page["ENC_RSN_POP_USE_YN"]?.ifBlank { null } ?: "N")
        set("S_ENC_T", page["ENC_T"].orEmpty())
        set("S_DSCLASS", sysApplCommon)
        set("S_DSMETHOD", method)
        set("S_FORWARD", "xsheetResultXML")
        set("S_PAGE_PROFILE_ID", page["PROFILE_ID"].orEmpty())
        set("S_PAGE_MODULE_ID", page["MODULE_ID"].orEmpty())
        set("S_PAGE_MENU_ID", page["MENU_ID"].orEmpty())
        set("S_PAGE_PGM_URL", page["PGM_URL"].orEmpty())
        set("S_PAGE_POP_URL", page["POP_URL"].orEmpty())
        set("S_PAGE_PGM_ID", page["PGM_ID"].orEmpty())
        set("S_PAGE_ENC_VAL", page["ENC_VAL"].orEmpty())
    }

    /** 신청서 고유 동작 호출: ajaxSyncRequestXS($("#S_DSCLASS").val(), method, serializeForm("f1")) */
    fun formRequest(method: String, f1: Map<String, String>): LinkedMultiValueMap<String, String> =
        LinkedMultiValueMap<String, String>().apply {
            f1.forEach { (k, v) -> set(k, v) }
            commonForm(method).forEach { (k, v) -> put(k, v) }
            set("S_DSCLASS", formDsClass)
        }

    companion object {
        private val PAGE_KEYS = listOf(
            "PROFILE_ID", "MODULE_ID", "MENU_ID", "PGM_URL", "POP_URL", "PGM_ID", "ENC_VAL",
            "ENC_MASKING_USE_YN", "ENC_PER_NO_LIMIT_YN", "ENC_RSN_POP_USE_YN", "ENC_T",
        )
        private val OTP_BLOCK = Regex("ApplyOtpVal\\s*=\\s*\\{([\\s\\S]*?)\\}")
        private val CASE = Regex("case\\s+\"([^\"]{20,})\"\\s*:")

        /** KEY : "값" — 앞에 다른 글자가 붙은 키(X_PGM_URL 등)와 섞이지 않게 경계를 확인한다 */
        private fun keyValue(source: String, key: String): String? =
            Regex("(?<![A-Za-z0-9_])" + Regex.escape(key) + "\\s*:\\s*[\"']([^\"']*)[\"']")
                .find(source)?.groupValues?.get(1)

        /** function doAction2 본문의 case 들 */
        private fun doAction2Cases(html: String): List<Pair<String, String>> {
            val start = html.indexOf("function doAction2")
            if (start < 0) return emptyList()
            val end = html.indexOf("\nfunction ", start + 20).let { if (it < 0) html.length else it }
            val body = html.substring(start, end)
            val matches = CASE.findAll(body).toList()
            return matches.mapIndexed { i, m ->
                val blockEnd = if (i + 1 < matches.size) matches[i + 1].range.first else body.length
                m.groupValues[1] to body.substring(m.range.last + 1, blockEnd)
            }
        }

        /** 주석 처리된 옛 코드(/* ... */)에 같은 case 가 남아 있어 먼저 걷어낸다 */
        private val BLOCK_COMMENT = Regex("/\\*[\\s\\S]*?\\*/")

        fun parse(html: String): ApplicationFormMetadata {
            fun required(name: String, value: String?): String =
                value?.takeIf { it.isNotBlank() }
                    ?: throw JadeHrParseException("JadeHR 신청서 화면에서 $name 을(를) 찾지 못했습니다.")

            val document = Jsoup.parse(html)
            val otpBlock = OTP_BLOCK.find(html)?.groupValues?.get(1).orEmpty()
            val page = PAGE_KEYS.mapNotNull { key -> keyValue(html, key)?.let { key to it } }.toMap()
            val f1 = document.selectFirst("form#f1") ?: document.selectFirst("form[name=f1]")
            val f1Fields = linkedMapOf<String, String>()
            f1?.select("input[name], select[name], textarea[name]")?.forEach { el ->
                val name = el.attr("name")
                val value = when (el.tagName()) {
                    "select" -> (el.selectFirst("option[selected]") ?: el.selectFirst("option"))?.attr("value").orEmpty()
                    "textarea" -> el.wholeText()
                    else -> {
                        val type = el.attr("type").lowercase()
                        if ((type == "checkbox" || type == "radio") && !el.hasAttr("checked")) return@forEach
                        if (type == "button" || type == "submit" || type == "file") return@forEach
                        el.attr("value")
                    }
                }
                f1Fields[name] = value
            }

            return ApplicationFormMetadata(
                programOpenTime = required("S_PGM_OPEN_TIME", html.extractJavaScriptString("S_PGM_OPEN_TIME")),
                encryptedOtpKey = required("S_ENC_OTP_KEY", html.extractJavaScriptString("S_ENC_OTP_KEY")),
                csrfSalt = required("S_CSRF_SALT", html.extractJavaScriptString("S_CSRF_SALT")),
                sysApplCommon = required("ApplyOtpVal.Sys_appl_common", keyValue(otpBlock, "Sys_appl_common")),
                getInitInfoMethod = required("ApplyOtpVal.getInitInfo", keyValue(otpBlock, "getInitInfo")),
                getCourseMethod = required("ApplyOtpVal.getCourse", keyValue(otpBlock, "getCourse")),
                searchRetireeMethod = required("ApplyOtpVal.searchRetiree", keyValue(otpBlock, "searchRetiree")),
                apply01Method = required("ApplyOtpVal.apply01", keyValue(otpBlock, "apply01")),
                loginEmpId = required("S_LOGIN_EMP_ID", document.selectFirst("#S_LOGIN_EMP_ID")?.attr("value")),
                loginEmpName = document.selectFirst("#S_LOGIN_EMP_NM")?.attr("value").orEmpty(),
                formDsClass = required("S_DSCLASS", f1?.selectFirst("#S_DSCLASS")?.attr("value") ?: document.selectFirst("#S_DSCLASS")?.attr("value")),
                f1Fields = f1Fields,
                cases = doAction2Cases(BLOCK_COMMENT.replace(html, "")),
                scripts = BLOCK_COMMENT.replace(
                    document.select("script").toList().filter { !it.hasAttr("src") }.joinToString("\n") { it.data() },
                    "",
                ),
                page = page,
            )
        }
    }
}

/** JadeHR 신청서 화면 스크립트에서 호출 정보를 꺼내는 도구 (메서드 값은 페이지마다 암호화돼 바뀐다) */
private object JsSource {
    private val DO_ACTION2 = Regex("doAction2\\(\\s*[\"']([^\"']{20,})[\"']\\s*\\)")
    private val LITERAL_CALL = Regex("ajaxSyncRequestXS\\(\\s*\\$\\(\"#S_DSCLASS\"\\)\\.val\\(\\)\\s*,\\s*\"([^\"]{20,})\"")

    /** function name(...) { ... } 의 본문 (중괄호 짝 맞춤) */
    fun functionBody(js: String, name: String): String {
        val start = Regex("function\\s+" + Regex.escape(name) + "\\s*\\(").find(js)?.range?.first ?: return ""
        val open = js.indexOf('{', start)
        if (open < 0) return ""
        var depth = 0
        for (i in open until js.length) {
            when (js[i]) {
                '{' -> depth++
                '}' -> if (--depth == 0) return js.substring(start, i + 1)
            }
        }
        return js.substring(start)
    }

    /** switch 안 case "LABEL": 부터 다음 case "...": 전까지 (짧은 평문 라벨용) */
    fun caseSegment(body: String, label: String): String? {
        val start = body.indexOf("case \"$label\"").takeIf { it >= 0 } ?: return null
        val next = Regex("case\\s+\"[A-Z_]+\"\\s*:").find(body, start + label.length + 7)?.range?.first ?: body.length
        return body.substring(start, next)
    }

    fun doAction2Keys(segment: String): List<String> = DO_ACTION2.findAll(segment).map { it.groupValues[1] }.toList()

    private val CLASS_CALL = Regex("ajaxSyncRequestXS\\(\\s*\"([^\"]{20,})\"\\s*,\\s*\"([^\"]{20,})\"")

    /** ajaxSyncRequestXS("클래스", "메서드", ...) 호출들: (클래스, 메서드, 뒤따르는 코드 일부) */
    fun classCalls(segment: String): List<Triple<String, String, String>> =
        CLASS_CALL.findAll(segment).map { m ->
            Triple(m.groupValues[1], m.groupValues[2], segment.substring(m.range.last, minOf(segment.length, m.range.last + 700)))
        }.toList()

    /** ajaxSyncRequestXS($("#S_DSCLASS").val(), "메서드", ...) 호출들: (메서드, 뒤따르는 코드 일부) */
    fun literalCalls(segment: String): List<Pair<String, String>> =
        LITERAL_CALL.findAll(segment).map { m ->
            m.groupValues[1] to segment.substring(m.range.last, minOf(segment.length, m.range.last + 700))
        }.toList()
}

/** 열린 신청서 하나의 상태 */
private data class ApplicationContext(
    val cookieJar: JadeHrCookieJar,
    val form: ApplicationFormMetadata,
    val txToken: String,
    val procFlag: String,
    val infSendAppl: String,
    val members: List<ApprovalLineMember>,
    val initFields: Map<String, String>,
)

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
            // xsheet 표 형식: <HTR> 의 TD 가 컬럼명, 첫 <TR> 의 TD 가 값
            val headerRow = children().firstOrNull { it.tagName().equals("HTR", ignoreCase = true) }
            val dataRow = children().firstOrNull { it.tagName().equals("TR", ignoreCase = true) }
            if (headerRow != null && dataRow != null) {
                val headers = headerRow.children().map { it.wholeText().trim() }
                val values = dataRow.children().map { it.wholeText().trim() }
                return headers.zip(values)
                    .filter { (key, _) -> key.isNotBlank() }
                    .toMap()
            }

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
    val programOpenTime: String?,
    val encryptedOtpKey: String?,
    val csrfSalt: String?,
    val pageProgramUrl: String?,
    val pageProgramId: String?,
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
                programOpenTime = html.extractJavaScriptString("S_PGM_OPEN_TIME"),
                encryptedOtpKey = html.extractJavaScriptString("S_ENC_OTP_KEY"),
                csrfSalt = html.extractJavaScriptString("S_CSRF_SALT"),
                pageProgramUrl = html.extractPageString("PGM_URL"),
                pageProgramId = html.extractPageString("PGM_ID"),
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

        /** 메인 HTML 에서 특정 메뉴의 정보를 읽는다. 없으면 null */
        fun parse(html: String, menuId: String): AttendanceMenuMetadata? =
            Jsoup.parse(html).selectFirst("""span[menu_id="$menuId"]""")?.let { from(it, it.attr("menu_nm")) }

        fun parse(html: String): AttendanceMenuMetadata {
            val element = Jsoup.parse(html).selectFirst("""span[menu_id="$ATTENDANCE_MENU_ID"]""")
                ?: return DEFAULT.also {
                    LoggerFactory.getLogger(AttendanceMenuMetadata::class.java).warn(
                        "JadeHR attendance menu metadata was not found in main HTML. Use default menu metadata.",
                    )
                }

            return from(element, "근태현황")
        }

        private fun from(element: org.jsoup.nodes.Element, defaultMenuName: String): AttendanceMenuMetadata {
            return AttendanceMenuMetadata(
                profileId = element.requiredAttr("profile_id"),
                moduleId = element.requiredAttr("module_id"),
                menuId = element.requiredAttr("menu_id"),
                menuName = element.attrOrDefault("menu_nm", defaultMenuName),
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

        /** 신청/결재 > 신청 메뉴 고정값 (2026-11-10 JadeHR 메뉴 목록에서 확인) */
        val APPL_DEFAULT = AttendanceMenuMetadata(
            profileId = "ESS",
            moduleId = "essAppl",
            menuId = "essappl103",
            menuName = "신청",
            programUrl = "/sys/sy_appl/sy_appl_200_m03.jsp",
            programId = "sy_appl_200_m03",
            employeeSearchAuthCode = "10",
            generatedYn = "Y",
            encValue = "6D7BB69C365EC267F1C2D7D3F1308487F182D203423E75CB090209489F53EADE",
            encValue2 = "6D7BB69C365EC267F1C2D7D3F1308487F182D203423E75CB090209489F53EADE",
            maskingUseYn = "N",
            personalNumberLimitYn = "N",
            reasonPopupUseYn = "N",
            encMaskingUseYn = "",
            encPersonalNumberLimitYn = "",
            encReasonPopupUseYn = "fU87TxZiEA22XRo7gwRaPNO0s+XSvTQGAl3v9AgqffyL4abBuj1pLY7qQpVDaka5",
            encT = "",
            excelFilePasswordYn = "N",
            confirmInputUseYn = "N",
            clipboardYn = "N",
            loginInfo = null,
        )

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
