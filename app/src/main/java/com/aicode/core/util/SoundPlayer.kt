package com.aicode.core.util

import android.content.Context
import android.media.MediaPlayer
import android.net.Uri
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * 简单提示音播放器：用 MediaPlayer 播放本地音频文件。
 * 不常驻，每次播放完释放资源。
 */
object SoundPlayer {
    private const val TAG = "SoundPlayer"

    suspend fun play(context: Context, soundPath: String?) {
        if (soundPath.isNullOrBlank()) return
        val file = File(soundPath)
        if (!file.exists()) {
            FileLogger.w(TAG, "音频文件不存在: $soundPath")
            return
        }
        withContext(Dispatchers.IO) {
            var player: MediaPlayer? = null
            try {
                player = MediaPlayer().apply {
                    setDataSource(context, Uri.fromFile(file))
                    isLooping = false
                    prepare()
                    setOnCompletionListener { it.release() }
                    setOnErrorListener { mp, _, _ ->
                        mp.release()
                        true
                    }
                    start()
                }
            } catch (e: Exception) {
                FileLogger.e(TAG, "播放音频失败: ${e.message}", e)
                player?.release()
            }
        }
    }
}
