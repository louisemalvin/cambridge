package dev.cambridge.sender.connection.control.cambridge

import dev.cambridge.sender.connection.control.ReceiverControlError
import dev.cambridge.sender.connection.control.ReceiverControlException
import dev.cambridge.sender.connection.control.ReceiverProbe
import dev.cambridge.sender.model.ReceiverCapabilities
import dev.cambridge.sender.model.ReceiverEndpoint
import dev.cambridge.sender.connection.control.cambridge.CamBridgeStreamContract.requireCapabilities
import dev.cambridge.sender.connection.control.cambridge.CamBridgeStreamContract.stringField
import dev.cambridge.sender.connection.control.cambridge.CamBridgeStreamContract.stringFieldOrNull
import kotlinx.coroutines.CancellationException
import java.io.IOException
import java.util.UUID

class CamBridgeReceiverProbe : ReceiverProbe {
    override suspend fun probe(endpoint: ReceiverEndpoint): Result<ReceiverCapabilities> = try {
        val requestId = UUID.randomUUID().toString()
        CamBridgeControlConnection.connect(
            host = endpoint.host,
            port = endpoint.controlPort,
            readTimeoutMillis = CamBridgeStreamContract.REQUEST_TIMEOUT_MILLIS,
        ).use { connection ->
            connection.send(CamBridgeStreamContract.probe(requestId))
            val response = connection.receive()
                ?: throw ReceiverControlException(
                    ReceiverControlError.Network("The receiver closed the probe connection"),
                )
            when (response.stringField("type")) {
                CamBridgeStreamContract.MESSAGE_CAPABILITIES ->
                    Result.success(response.requireCapabilities(requestId))
                CamBridgeStreamContract.MESSAGE_ERROR -> throw ReceiverControlException(
                    ReceiverControlError.Protocol(
                        response.stringFieldOrNull("error") ?: "The receiver rejected the probe",
                    ),
                )
                else -> throw ReceiverControlException(
                    ReceiverControlError.Protocol("The receiver returned an unexpected probe response"),
                )
            }
        }
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (cause: Throwable) {
        Result.failure(cause.asReceiverControlException())
    }

    private fun Throwable.asReceiverControlException(): ReceiverControlException = when (this) {
        is ReceiverControlException -> this
        is IOException -> ReceiverControlException(
            ReceiverControlError.Network(message ?: "The receiver could not be reached"),
            this,
        )
        is IllegalArgumentException,
        is IllegalStateException,
        -> ReceiverControlException(
            ReceiverControlError.Protocol(message ?: "The receiver returned an invalid probe response"),
            this,
        )
        else -> ReceiverControlException(ReceiverControlError.Unexpected(this), this)
    }
}
