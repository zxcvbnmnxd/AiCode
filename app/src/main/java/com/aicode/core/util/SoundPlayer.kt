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
        withContext(Dispatchers.IO) {
            var player: MediaPlayer? = null
            try {
                player = when {
                    !soundPath.isNullOrBlank() && File(soundPath).exists() -> {
                        // 用户自定义路径
                        MediaPlayer().apply {
                            setDataSource(context, Uri.fromFile(File(soundPath)))
                        }
                    }
                    else -> {
                        // 默认从 assets 读取（soundPath 传 "complete" / "error" / "notify"）
                        val assetName = when (soundPath) {
                            "error" -> "sounds/error.wav"
                            "notify" -> "sounds/notify.wav"
                            else -> "sounds/complete.wav"
                        }
                        val afd = context.assets.openFd(assetName)
                        MediaPlayer().apply {
                            setDataSource(afd.fileDescriptor, afd.startOffset, afd.length)
                            afd.close()
                        }
                    }
                }
                player.apply {
                    isLooping = false
                    prepare()
                    setOnCompletionListener { it.release() }
                    setOnErrorListener { mp, _, _ ->
                        mp.release()
                        true
                    }
                    start()
                }
                FileLogger.i(TAG, "提示音播放成功: $soundPath")
            } catch (e: Exception) {
                FileLogger.e(TAG, "播放音频失败: ${e.message}", e)
                player?.release()
            }
        }
    }
}
