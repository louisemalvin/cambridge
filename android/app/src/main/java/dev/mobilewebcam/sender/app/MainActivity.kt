package dev.mobilewebcam.sender.app

import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import dagger.hilt.android.AndroidEntryPoint
import dev.mobilewebcam.sender.connection.SenderConnectionCoordinator
import dev.mobilewebcam.sender.model.ReceiverEndpoint
import dev.mobilewebcam.sender.model.SenderSettingsRepository
import javax.inject.Inject

@AndroidEntryPoint
class MainActivity : ComponentActivity() {
    @Inject
    lateinit var coordinator: SenderConnectionCoordinator

    @Inject
    lateinit var settings: SenderSettingsRepository

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        configureReceiverOriginFromIntent()
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        setContent { SenderApp(coordinator, settings) }
    }

    private fun configureReceiverOriginFromIntent() {
        val host = intent.getStringExtra(EXTRA_RECEIVER_HOST) ?: return
        val controlPort = intent.getIntExtra(EXTRA_RECEIVER_CONTROL_PORT, INVALID_PORT)
        val displayName = intent.getStringExtra(EXTRA_RECEIVER_NAME) ?: DEFAULT_RECEIVER_NAME
        val controlToken = intent.getStringExtra(EXTRA_RECEIVER_TOKEN)
        val endpoint = ReceiverEndpoint(
            host = host,
            controlPort = controlPort,
            displayName = displayName,
            controlToken = controlToken,
        ).takeIf(ReceiverEndpoint::isValid)
        endpoint?.let(settings::updateReceiverEndpoint)
    }

    private companion object {
        const val EXTRA_RECEIVER_HOST = "dev.mobilewebcam.sender.receiverHost"
        const val EXTRA_RECEIVER_CONTROL_PORT = "dev.mobilewebcam.sender.receiverControlPort"
        const val EXTRA_RECEIVER_NAME = "dev.mobilewebcam.sender.receiverName"
        const val EXTRA_RECEIVER_TOKEN = "dev.mobilewebcam.sender.receiverToken"
        const val INVALID_PORT = -1
        const val DEFAULT_RECEIVER_NAME = "Receiver"
    }
}
