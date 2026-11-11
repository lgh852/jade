package co.kr.ghlee.jade.api.jadehr.presentation

import co.kr.ghlee.jade.api.jadehr.application.WorkPlanRunView
import co.kr.ghlee.jade.api.jadehr.application.WorkPlanService
import co.kr.ghlee.jade.api.jadehr.application.WorkPlanSettingView
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.PutMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController
import java.time.LocalTime

@RestController
@RequestMapping("/api/jadehr/work-plan")
class WorkPlanController(
    private val workPlanService: WorkPlanService,
) {

    @GetMapping
    suspend fun get(@RequestParam id: String): WorkPlanSettingView = workPlanService.get(id.trim())

    @PutMapping
    suspend fun save(@RequestParam id: String, @RequestBody request: WorkPlanSettingRequest): WorkPlanSettingView =
        workPlanService.save(id.trim(), request.enabled, request.startTime.toTime("시작"), request.endTime.toTime("종료"))

    /** 지금 실행. dryRun=true 면 등록할 날만 확인 */
    @PostMapping("/run")
    suspend fun run(
        @RequestParam id: String,
        @RequestParam(defaultValue = "true") dryRun: Boolean,
        @RequestParam(required = false) startTime: String?,
        @RequestParam(required = false) endTime: String?,
    ): WorkPlanRunView =
        workPlanService.run(id.trim(), dryRun, startTime?.toTime("시작"), endTime?.toTime("종료"))

    private fun String.toTime(label: String): LocalTime {
        require(Regex("([01]\\d|2[0-3]):[0-5]\\d").matches(trim())) { "$label 시각은 HH:mm 형식이어야 합니다." }
        return LocalTime.parse(trim())
    }
}

data class WorkPlanSettingRequest(
    val enabled: Boolean = false,
    val startTime: String = "09:30",
    val endTime: String = "18:30",
)
