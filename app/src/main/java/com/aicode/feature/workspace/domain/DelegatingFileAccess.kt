package com.aicode.feature.workspace.domain

import com.aicode.feature.agent.domain.container.LinuxContainerEngine
import com.aicode.feature.settings.data.repository.ExecutionMode
import com.aicode.feature.settings.data.repository.ExecutionModeHolder
import java.io.File
import java.io.InputStream
import java.nio.charset.Charset
import javax.inject.Inject
import javax.inject.Singleton

/**
 * [FileAccessProvider] 的委托层：同时持有本地、远程与 root shell 三套实现，每次方法调用时按
 * 当前执行模式与 AI root 命令模式转发到对应实现。
 *
 * - 远程 SSH → [RemoteSftpFileAccess]；
 * - AI root 命令模式 → [RootShellFileAccess]（经 su 操作宿主 /data 等目录）；
 * - 其余本地容器模式 → [LocalFileAccess]。
 *
 * 这样 Hilt 注入时机不再影响最终行为——无论 [FileAccessProvider] 在何时被首次注入，
 * 真正读写文件时才读取当前模式。
 */
@Singleton
class DelegatingFileAccess @Inject constructor(
    private val modeHolder: ExecutionModeHolder,
    private val localFileAccess: LocalFileAccess,
    private val remoteSftpFileAccess: RemoteSftpFileAccess,
    private val rootShellFileAccess: RootShellFileAccess,
    private val containerEngine: LinuxContainerEngine
) : FileAccessProvider {

    private fun delegate(): FileAccessProvider =
        when {
            modeHolder.currentMode() == ExecutionMode.REMOTE_SSH -> remoteSftpFileAccess
            containerEngine.rootCommandMode -> rootShellFileAccess
            else -> localFileAccess
        }

    override fun readFile(path: String): String = delegate().readFile(path)

    override fun readLines(path: String): Sequence<String> = delegate().readLines(path)

    override fun writeFile(path: String, content: String, overwrite: Boolean, encoding: Charset) =
        delegate().writeFile(path, content, overwrite, encoding)

    override fun exists(path: String): Boolean = delegate().exists(path)

    override fun isDirectory(path: String): Boolean = delegate().isDirectory(path)

    override fun isFile(path: String): Boolean = delegate().isFile(path)

    override fun fileSize(path: String): Long = delegate().fileSize(path)

    override fun lastModified(path: String): Long = delegate().lastModified(path)

    override fun fingerprint(path: String): String? = delegate().fingerprint(path)

    override fun permissions(path: String): String = delegate().permissions(path)

    override fun listFiles(path: String): List<FileEntry> = delegate().listFiles(path)

    override fun listFilesRecursive(path: String, maxDepth: Int): List<String> =
        delegate().listFilesRecursive(path, maxDepth)

    override fun readBytes(path: String): ByteArray = delegate().readBytes(path)

    override fun writeBytes(path: String, bytes: ByteArray, overwrite: Boolean) =
        delegate().writeBytes(path, bytes, overwrite)

    override fun writeStream(path: String, input: InputStream, overwrite: Boolean): Long =
        delegate().writeStream(path, input, overwrite)

    override fun copyToLocal(path: String): File = delegate().copyToLocal(path)

    override fun delete(path: String) = delegate().delete(path)

    override fun deleteRecursively(path: String) = delegate().deleteRecursively(path)

    override fun rename(path: String, newPath: String) = delegate().rename(path, newPath)

    override fun copy(path: String, newPath: String, overwrite: Boolean) = delegate().copy(path, newPath, overwrite)

    override fun move(path: String, newPath: String, overwrite: Boolean) = delegate().move(path, newPath, overwrite)

    override fun mkdirs(path: String) = delegate().mkdirs(path)

    override fun parentPath(path: String): String? = delegate().parentPath(path)

    override fun toDisplayPath(path: String): String = delegate().toDisplayPath(path)
}
