package dev.cambridge.sender.app

import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import dagger.hilt.android.AndroidEntryPoint
import dev.cambridge.sender.connection.SenderConnectionCoordinator
import dev.cambridge.sender.model.ReceiverEndpoint
import dev.cambridge.sender.model.SenderSettingsRepository
import dev.cambridge.sender.model.StreamOrientation
import dev.cambridge.sender.session.VideoProfiles
import javax.inject.Inject

@AndroidEntryPoint
class MainActivity : ComponentActivity() {
    @Inject
    lateinit var settings: SenderSettingsRepository

    @Inject
    lateinit var connectionCoordinator: SenderConnectionCoordinator

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        configureTestOrientationFromIntent()
        configureProfileFromIntent()
        configureReceiverOriginFromIntent()
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        setContent { SenderApp(settings, connectionCoordinator) }
    }

    private fun configureProfileFromIntent() {
        val profileId = intent.getStringExtra(EXTRA_PROFILE_ID) ?: return
        VideoProfiles.all.firstOrNull { profile -> profile.id == profileId }
            ?.let(settings::updateProfile)
    }

    private fun configureTestOrientationFromIntent() {
        val rotationDegrees = intent.getIntExtra(EXTRA_ROTATION_DEGREES, INVALID_ROTATION)
        val streamOrientation = when (rotationDegrees) {
            PORTRAIT_ROTATION_DEGREES -> StreamOrientation.PORTRAIT
            LANDSCAPE_ROTATION_DEGREES -> StreamOrientation.LANDSCAPE
            REVERSE_PORTRAIT_ROTATION_DEGREES -> StreamOrientation.PORTRAIT_REVERSED
            REVERSE_LANDSCAPE_ROTATION_DEGREES -> StreamOrientation.LANDSCAPE_REVERSED
            else -> null
        }
        streamOrientation?.let(settings::updateStreamOrientation)
    }

    private fun configureReceiverOriginFromIntent() {
        val host = intent.getStringExtra(EXTRA_RECEIVER_HOST) ?: return
        val controlPort = intent.getIntExtra(EXTRA_RECEIVER_CONTROL_PORT, INVALID_PORT)
        val displayName = intent.getStringExtra(EXTRA_RECEIVER_NAME) ?: DEFAULT_RECEIVER_NAME
        val endpoint = ReceiverEndpoint(
            host = host,
            controlPort = controlPort,
            displayName = displayName,
        ).takeIf(ReceiverEndpoint::isValid)
        endpoint?.let(settings::updateReceiverEndpoint)
    }

    private companion object {
        const val EXTRA_RECEIVER_HOST = "dev.cambridge.sender.receiverHost"
        const val EXTRA_RECEIVER_CONTROL_PORT = "dev.cambridge.sender.receiverControlPort"
        const val EXTRA_RECEIVER_NAME = "dev.cambridge.sender.receiverName"
        const val EXTRA_PROFILE_ID = "dev.cambridge.sender.profileId"
        const val EXTRA_ROTATION_DEGREES = "dev.cambridge.sender.rotationDegrees"
        const val INVALID_PORT = -1
        const val INVALID_ROTATION = -1
        const val PORTRAIT_ROTATION_DEGREES = 0
        const val LANDSCAPE_ROTATION_DEGREES = 90
        const val REVERSE_PORTRAIT_ROTATION_DEGREES = 180
        const val REVERSE_LANDSCAPE_ROTATION_DEGREES = 270
        const val DEFAULT_RECEIVER_NAME = "Receiver"
    }
}
