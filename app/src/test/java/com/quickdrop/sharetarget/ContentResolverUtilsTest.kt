package com.quickdrop.sharetarget

import org.junit.Assert.assertNull
import org.junit.Test

class ContentResolverUtilsTest {

    @Test
    fun `getDisplayName returns null for non-content uri when lastPathSegment null`() {
        // Basic JVM-side sanity check; real behavior is covered by instrumentation.
        assertNull(ContentResolverUtils.getDisplayName(FakeContentResolver(), FakeContentResolver.NULL_URI))
    }
}

// Minimal fakes to keep JVM unit test compilation-only.
// (Android framework classes are unavailable in local JVM tests.)
private class FakeContentResolver : android.content.ContentResolver(null) {
    override fun query(
        uri: android.net.Uri,
        projection: Array<out String>?,
        selection: String?,
        selectionArgs: Array<out String>?,
        sortOrder: String?
    ): android.database.Cursor? = null

    override fun openInputStream(uri: android.net.Uri): java.io.InputStream? = null

    companion object {
        val NULL_URI: android.net.Uri = android.net.Uri.parse("file://")
    }
}

