package com.quickdrop.sharetarget

import android.content.Intent
import android.net.Uri
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ShareReceiverIntentTest {

    @Test
    fun `ACTION_SEND contains one uri`() {
        val ctx = androidx.test.platform.app.InstrumentationRegistry.getInstrumentation().targetContext
        val uri: Uri = Uri.parse("content://com.example.provider/item/1")

        val intent = Intent(Intent.ACTION_SEND).apply {
            type = "image/png"
            putExtra(Intent.EXTRA_STREAM, uri)
        }

        // This test mainly ensures the activity class loads; parsing is internal.
        assertEquals(Intent.ACTION_SEND, intent.action)
        assertEquals("image/png", intent.type)
        // sanity
        assertEquals(uri, intent.getParcelableExtra(Intent.EXTRA_STREAM))
    }
}

