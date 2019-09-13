package com.dc2f.api.edit.ktor

import io.ktor.application.*
import io.ktor.features.origin
import io.ktor.http.*
import io.ktor.request.*
import io.ktor.util.AttributeKey
import io.ktor.util.pipeline.PipelinePhase
import mu.KLogging
import kotlin.system.measureTimeMillis

class CustomCallLogFeature {

    companion object : ApplicationFeature<Application, Unit, CustomCallLogFeature>, KLogging() {
        override val key = AttributeKey<CustomCallLogFeature>("CustomCallLogFeature")

        override fun install(pipeline: Application, configure: Unit.() -> Unit): CustomCallLogFeature {
            val loggingPhase = PipelinePhase("Logging")
            val feature = CustomCallLogFeature()
            pipeline.insertPhaseBefore(ApplicationCallPipeline.Monitoring, loggingPhase)
            pipeline.intercept(loggingPhase) {
                val time = measureTimeMillis {
                    proceed()
                }
                feature.logSuccess(time, call)
            }
            return feature

        }

    }

    private fun logSuccess(
        time: Long,
        call: ApplicationCall
    ) {
        val status = call.response.status() ?: "Unhandled"
        when (status) {
            HttpStatusCode.Found -> logger.info("CALL[${time.toString().padStart(5)}] $status: ${logString(call)} -> ${call.response.headers[HttpHeaders.Location]}")
            else -> logger.info { "CALL[${time.toString().padStart(5)}] $status: ${logString(call)}" }
        }
    }

    private fun logString(call: ApplicationCall): String {
        val request = call.request

        fun quote(str: String?): String {
            return "\"${str ?: ""}\""
        }

        val args = arrayOf(
            request.httpMethod.value,
            quote(request.origin.uri),
            request.local.remoteHost,
            quote(request.header(HttpHeaders.XForwardedFor)),
            quote(request.header(HttpHeaders.Referrer)),
            quote(request.userAgent())
        )
        return args.joinToString(" ")
    }


}