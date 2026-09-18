package chat.mural.network

import android.content.Context
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.util.UUID
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class NativeCompatibilityTest {
    @Test fun keystoreEncryptsAndDeletesOnlyIsolatedTestCredentials() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val namespace = UUID.randomUUID().toString()
        val store = CredentialStore(context, "mural_test_$namespace", "chat.mural.test.$namespace")
        val fake = "sk-offline-test-credential-never-sent"
        try {
            store.save(fake)
            val raw = context.getSharedPreferences("mural_test_$namespace", Context.MODE_PRIVATE).all.values.joinToString()
            assertFalse(raw.contains(fake))
            assertTrue(store.hasKey)
            assertEquals(fake, CredentialStore(context, "mural_test_$namespace", "chat.mural.test.$namespace").read())
            assertThrows(CredentialStore.CredentialException.Invalid::class.java) { store.save("bad") }
            assertEquals(fake, store.read())
            store.delete(); assertFalse(store.hasKey)
        } finally { store.delete() }
    }

    @Test fun legacyProviderKeyIsNotLoadedAsADeepSeekCredential() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        check(context.packageName.endsWith(".uitest"))
        val legacy = CredentialStore(context, "mural_openai_credentials", "chat.mural.openai.aes")
        val current = CredentialStore(context)
        try {
            current.delete()
            legacy.save("sk-offline-legacy-test-never-sent")
            assertFalse(current.hasKey)
            current.save("sk-offline-deepseek-test-never-sent")
            assertEquals("sk-offline-deepseek-test-never-sent", current.read())
            assertEquals("sk-offline-legacy-test-never-sent", legacy.read())
        } finally { legacy.delete(); current.delete() }
    }
}
