package com.blibla.animeshimejipetscreen.data.worker

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import com.blibla.animeshimejipetscreen.data.local.DbProvider
import com.blibla.animeshimejipetscreen.util.ZipUtils
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File

class DownloadShimejiWorker(
    context: Context,
    params: WorkerParameters
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val id = inputData.getLong("id", -1L)
        if (id <= 0L) return Result.failure()

        val db = DbProvider.get(applicationContext)
        val dao = db.shimejiDao()
        val item = dao.getById(id) ?: return Result.failure()

        val baseDir = File(applicationContext.filesDir, "shimeji/$id")
        val zipFile = File(baseDir, "set.zip")
        val extractDir = File(baseDir, "extracted")

        try {
            if (!baseDir.exists()) baseDir.mkdirs()
            if (extractDir.exists()) extractDir.deleteRecursively()

            // reset progress
            setProgress(workDataOf("progress" to 0))

            val client = OkHttpClient()
            val request = Request.Builder().url(item.zipUrl).build()
            val response = client.newCall(request).execute()
            if (!response.isSuccessful) return Result.retry()

            val body = response.body ?: return Result.retry()
            val total = body.contentLength().coerceAtLeast(1L)

            body.byteStream().use { input ->
                zipFile.outputStream().use { output ->
                    val buffer = ByteArray(8 * 1024)
                    var downloaded = 0L
                    var read: Int

                    while (input.read(buffer).also { read = it } != -1) {
                        output.write(buffer, 0, read)
                        downloaded += read

                        val p = ((downloaded * 100) / total).toInt().coerceIn(0, 100)
                        setProgress(workDataOf("progress" to p))
                    }
                    output.flush()
                }
            }

            // extract
            ZipUtils.unzip(zipFile, extractDir)

            // mark ready in DB
            dao.markReady(id = id, localUpdatedAt = item.zipUpdatedAt)

            setProgress(workDataOf("progress" to 100))
            return Result.success()
        } catch (e: Exception) {
            e.printStackTrace()
            return Result.retry()
        }
    }
}
