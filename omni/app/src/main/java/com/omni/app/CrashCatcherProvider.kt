package com.omni.app

import android.content.ContentProvider
import android.content.ContentValues
import android.database.Cursor
import android.net.Uri
import android.os.Build
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Installs first (android:initOrder=100, ahead of androidx.startup's
 * InitializationProvider) and arms a process-wide uncaught-exception handler.
 *
 * When anything crashes the process — including third-party startup
 * initializers that run before MainActivity — the full stack trace is written
 * to filesDir/crash-report.txt before the process dies. MainActivity shows
 * that file on the next launch, so even a 100% crash loop produces a
 * screenshot-able diagnosis instead of a mystery "keeps stopping".
 */
class CrashCatcherProvider : ContentProvider() {

    override fun onCreate(): Boolean {
        val appContext = context?.applicationContext
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            try {
                if (appContext != null) {
                    val report = buildString {
                        appendLine("OMNI crash report")
                        appendLine(
                            "time=" + SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US)
                                .format(Date()),
                        )
                        appendLine("device=" + Build.MANUFACTURER + " " + Build.MODEL)
                        appendLine(
                            "android=" + Build.VERSION.RELEASE +
                                " (sdk " + Build.VERSION.SDK_INT + ")",
                        )
                        appendLine("thread=" + thread.name)
                        appendLine()
                        appendLine(stackTraceOf(throwable))
                    }
                    File(appContext.filesDir, CRASH_FILE).writeText(report)
                }
            } catch (_: Throwable) {
                // Never crash the crash reporter.
            } finally {
                previous?.uncaughtException(thread, throwable)
            }
        }
        return true
    }

    private fun stackTraceOf(t: Throwable): String {
        val sb = StringBuilder()
        var cur: Throwable? = t
        var first = true
        while (cur != null) {
            if (!first) sb.append("Caused by: ")
            first = false
            sb.append(cur.javaClass.name).append(": ").append(cur.message).append('\n')
            for (el in cur.stackTrace) sb.append("    at ").append(el.toString()).append('\n')
            cur = cur.cause
        }
        return sb.toString()
    }

    override fun query(
        uri: Uri,
        projection: Array<String>?,
        selection: String?,
        selectionArgs: Array<String>?,
        sortOrder: String?,
    ): Cursor? = null

    override fun getType(uri: Uri): String? = null

    override fun insert(uri: Uri, values: ContentValues?): Uri? = null

    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<String>?): Int = 0

    override fun update(
        uri: Uri,
        values: ContentValues?,
        selection: String?,
        selectionArgs: Array<String>?,
    ): Int = 0

    companion object {
        const val CRASH_FILE = "crash-report.txt"
    }
}
