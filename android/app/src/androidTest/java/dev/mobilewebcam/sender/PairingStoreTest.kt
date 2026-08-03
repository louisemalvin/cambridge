package dev.mobilewebcam.sender

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import dev.mobilewebcam.sender.connection.discovery.PairingAuthentication
import dev.mobilewebcam.sender.connection.discovery.PairingStore
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PairingStoreTest {
    @Test
    fun approvedReceiverCanBeForgottenAndDoesNotAuthenticateAfterward() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val store = PairingStore(context)
        val receiverId = "pairing-store-test-receiver"

        store.forgetAll()
        store.approve(receiverId, "Test desktop", "127.0.0.1")

        try {
            assertTrue(store.hasApprovedReceivers())
            assertTrue(
                store.authentication(receiverId, null, "127.0.0.1") is
                    PairingAuthentication.PendingTokenDelivery,
            )

            store.forget(receiverId)

            assertFalse(store.hasApprovedReceivers())
            assertEquals(
                PairingAuthentication.Unpaired,
                store.authentication(receiverId, null, "127.0.0.1"),
            )
        } finally {
            store.forgetAll()
        }
    }
}
