package co.kr.ghlee.jade

import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.boot.context.properties.ConfigurationPropertiesScan
import org.springframework.boot.runApplication

@SpringBootApplication
@ConfigurationPropertiesScan
class JadeApplication

fun main(args: Array<String>) {
	runApplication<JadeApplication>(*args)
}
