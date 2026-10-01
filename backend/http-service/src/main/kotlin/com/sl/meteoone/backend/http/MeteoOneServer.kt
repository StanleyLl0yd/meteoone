package com.sl.meteoone.backend.http

import com.sl.meteoone.backend.orchestration.BackendForecastOrchestrator
import io.ktor.server.engine.embeddedServer
import io.ktor.server.netty.Netty
import java.nio.file.Path

private const val DEFAULT_HOST = "127.0.0.1"
private const val DEFAULT_PORT = 8080

internal data class MeteoOneServerRuntimeConfig(
    val host: String,
    val port: Int,
    val nativeBundleRoot: Path,
) {
    companion object {
        fun fromEnvironment(environment: Map<String, String>): MeteoOneServerRuntimeConfig {
            val host = environment["METEOONE_SERVER_HOST"]
                ?.takeIf(String::isNotBlank)
                ?: DEFAULT_HOST
            val port = environment["METEOONE_SERVER_PORT"]
                ?.toIntOrNull()
                ?: DEFAULT_PORT
            require(port in 1..65535) {
                "METEOONE_SERVER_PORT must be a valid TCP port"
            }
            val nativeBundle = environment["METEOONE_SERVER_NATIVE_BUNDLE"]
                ?.takeIf(String::isNotBlank)
                ?: error("METEOONE_SERVER_NATIVE_BUNDLE must point to a verified server native bundle")
            return MeteoOneServerRuntimeConfig(
                host = host,
                port = port,
                nativeBundleRoot = Path.of(nativeBundle).toAbsolutePath().normalize(),
            )
        }
    }
}

fun main() {
    val config = MeteoOneServerRuntimeConfig.fromEnvironment(System.getenv())
    val orchestrator = BackendForecastOrchestrator.production(
        serverNativeBundleRoot = config.nativeBundleRoot,
    )
    val handler = BackendForecastRequestHandler(orchestrator::forecast)

    embeddedServer(
        factory = Netty,
        host = config.host,
        port = config.port,
    ) {
        installMeteoOneHttpService(handler)
    }.start(wait = true)
}
