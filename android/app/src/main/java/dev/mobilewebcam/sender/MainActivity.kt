package dev.mobilewebcam.sender

import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import dev.mobilewebcam.sender.app.SenderApp
import dev.mobilewebcam.sender.connection.discovery.PairingStore
import dev.mobilewebcam.sender.connection.discovery.SenderConnectionCoordinator
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject

@AndroidEntryPoint
class MainActivity : ComponentActivity() {
    @Inject
    lateinit var pairings: PairingStore

    @Inject
    lateinit var coordinator: SenderConnectionCoordinator

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        setContent { SenderApp(pairings, coordinator) }
    }
}
