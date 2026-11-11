package co.kr.ghlee.jade

import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.boot.autoconfigure.security.reactive.ReactiveUserDetailsServiceAutoConfiguration
import org.springframework.boot.context.properties.ConfigurationPropertiesScan
import org.springframework.boot.runApplication
import org.springframework.scheduling.annotation.EnableScheduling

// 사용자 저장소는 JadeHR 이라 기본 사용자(생성 비밀번호)는 만들지 않는다
@SpringBootApplication(exclude = [ReactiveUserDetailsServiceAutoConfiguration::class])
@ConfigurationPropertiesScan
@EnableScheduling
class JadeApplication

fun main(args: Array<String>) {
	runApplication<JadeApplication>(*args)
}
