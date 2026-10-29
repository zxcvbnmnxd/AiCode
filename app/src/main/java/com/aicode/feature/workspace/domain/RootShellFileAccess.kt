package com.aicode.feature.workspace.domain

import com.aicode.feature.agent.domain.container.LinuxContainerEngine
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import java.io.InputStream
import java.nio.charset.Charset
import java.nio.file.FileAlreadyExistsException
import java.nio.file.NoSuchFileException
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

/**
 * [FileAccessProvider] 的 root shell 实现。
 *
 * 背景：root 命令模式下，App 自身进程（普通 uid）无权访问 `/data/项目/...` 等宿主工作区目录，
 * [LocalFileAccess] 的 java.io.File 调用会因权限不足而失败。本实现把所有文件操作转成
 * root shell（`su -c`）命令执行，由 root 身份完成读写，从而让 list / readFile / writeFile /
 * editFile / sendFile / viewImage 等专用工具在 root 模式下同样可用。
 *
 * 路径仍由 [WorkspacePathMapper] 先解析成宿主真实路径（root 模式下未命中工作区/配置目录的
 * 绝对路径按手机真实路径返回，见 mapper）；本类只负责在 root 身份下操作该真实路径。
 */
@Singleton
class RootShellFileAccess @Inject constructor(
    @ApplicationContext private val context: android.content.Context,
    private val pathMapper: WorkspacePathMapper,
    private val containerEngine: LinuxContainerEngine
) : FileAccessProvider {

    private companion object {
        const val TAG = "RootShellFileAccess"
        const val CMD_TIMEOUT_SECONDS = 60L
    }

    /** 把 AI 逻辑路径解析为宿主真实绝对路径。 */
    private fun host(path: String): File = pathMapper.toHostFile(path)

    private fun hostPath(path: String): String = host(path).absolutePath

    /** shell 单引号转义：路径原样包进单引号，内部单引号按 `'\''` 拆出转义。 */
    private fun shQuote(raw: String): String = "'" + raw.replace("'", "'\\''") + "'"

    private data class ShellResult(val exitCode: Int, val stdout: ByteArray, val stderr: String)

    /**
     * 以 root 身份执行一条 shell 命令；[stdinBytes] 非空时通过 stdin 喂给进程（配合 `cat > file`）。
     * 命令环境（HOME / PATH / Termux 叠加等）由 [LinuxContainerEngine.buildSuInvocation] 统一构造。
     */
    private fun exec(shellCmd: String, stdinBytes: ByteArray? = null): ShellResult {
        val invocation = containerEngine.buildSuInvocation(shellCmd, null)
        val pb = ProcessBuilder(invocation.argv)
        pb.redirectErrorStream(false)
        val process = pb.start()
        try {
            if (stdinBytes != null) {
                process.outputStream.use { out -> out.write(stdinBytes) }
            } else {
                process.outputStream.close()
            }
            val stdoutBox = arrayOf<ByteArray?>(null)
            val stdoutThread = Thread { stdoutBox[0] = process.inputStream.readBytes() }.apply {
                isDaemon = true
                start()
            }
            val stderr = process.errorStream.readBytes().toString(Charsets.UTF_8)
            val finished = process.waitFor(CMD_TIMEOUT_SECONDS, TimeUnit.SECONDS)
            if (!finished) {
                process.destroyForcibly()
                throw java.io.IOException("root shell 命令超时(${CMD_TIMEOUT_SECONDS}s): $shellCmd")
            }
            stdoutThread.join(2_000)
            return ShellResult(process.exitValue(), stdoutBox[0] ?: ByteArray(0), stderr)
        } finally {
            if (process.isAlive) process.destroyForcibly()
        }
    }

    /** 执行命令并要求退出码为 0；失败时把 stderr 并入 IOException。 */
    private fun execChecked(shellCmd: String, stdinBytes: ByteArray? = null) {
        val r = exec(shellCmd, stdinBytes)
        if (r.exitCode != 0) {
            throw java.io.IOException("root 命令失败(exit=${r.exitCode}): $shellCmd\n${r.stderr.trim()}")
        }
    }

    override fun readFile(path: String): String {
        val p = hostPath(path)
        if (!exists(path)) throw NoSuchFileException(File(p))
        val r = exec("cat ${shQuote(p)}")
        if (r.exitCode != 0) throw NoSuchFileException(File(p))
        return r.stdout.toString(Charsets.UTF_8)
    }

    override fun readLines(path: String): Sequence<String> {
        val p = hostPath(path)
        if (!exists(path)) throw NoSuchFileException(File(p))
        // 惰性 Sequence：每次迭代由底层 root 进程持续吐出行；迭代结束/取消时关闭进程。
        return sequence {
            val invocation = containerEngine.buildSuInvocation("cat ${shQuote(p)}", null)
            val pb = ProcessBuilder(invocation.argv)
            val process = pb.start()
            try {
                process.outputStream.close()
                process.inputStream.bufferedReader(Charsets.UTF_8).use { reader ->
                    var line: String? = reader.readLine()
                    while (line != null) {
                        yield(line)
                        line = reader.readLine()
                    }
                }
                process.waitFor(CMD_TIMEOUT_SECONDS, TimeUnit.SECONDS)
            } finally {
                if (process.isAlive) process.destroyForcibly()
            }
        }
    }

    override fun writeFile(path: String, content: String, overwrite: Boolean, encoding: Charset) {
        val p = hostPath(path)
        if (exists(path) && !overwrite) throw FileAlreadyExistsException(File(p))
        writeBytesInternal(p, content.toByteArray(encoding))
    }

    override fun exists(path: String): Boolean {
        val r = exec("[ -e ${shQuote(hostPath(path))} ]")
        return r.exitCode == 0
    }

    override fun isDirectory(path: String): Boolean {
        val r = exec("[ -d ${shQuote(hostPath(path))} ]")
        return r.exitCode == 0
    }

    override fun isFile(path: String): Boolean {
        val r = exec("[ -f ${shQuote(hostPath(path))} ]")
        return r.exitCode == 0
    }

    override fun fileSize(path: String): Long {
        val r = exec("stat -c %s ${shQuote(hostPath(path))} 2>/dev/null")
        if (r.exitCode != 0) return 0L
        return r.stdout.toString(Charsets.UTF_8).trim().toLongOrNull() ?: 0L
    }

    override fun lastModified(path: String): Long {
        val r = exec("stat -c %Y ${shQuote(hostPath(path))} 2>/dev/null")
        if (r.exitCode != 0) return 0L
        val seconds = r.stdout.toString(Charsets.UTF_8).trim().toLongOrNull() ?: return 0L
        return seconds * 1000L
    }

    override fun fingerprint(path: String): String? {
        if (!isFile(path)) return null
        return sha256Fingerprint(readBytes(path))
    }

    override fun permissions(path: String): String {
        val p = shQuote(hostPath(path))
        val r = if (exec("[ -r $p ]").exitCode == 0) "r" else "-"
        val w = if (exec("[ -w $p ]").exitCode == 0) "w" else "-"
        val x = if (exec("[ -x $p ]").exitCode == 0) "x" else "-"
        return r + w + x
    }

    override fun listFiles(path: String): List<FileEntry> {
        val dir = hostPath(path)
        if (!isDirectory(path)) return emptyList()
        // 一条命令内对每个条目输出 type / size / mtime / name，减少反复拉起 su 的次数。
        val script = buildString {
            append("find ${shQuote(dir)} -mindepth 1 -maxdepth 1 | while IFS= read -r f; do ")
            append("t=file; [ -d \"\$f\" ] && t=dir; ")
            append("s=\$(stat -c %s \"\$f\" 2>/dev/null); m=\$(stat -c %Y \"\$f\" 2>/dev/null); ")
            append("n=\$(basename \"\$f\"); ")
            append("printf '%s\\t%s\\t%s\\t%s\\n' \"\$t\" \"\${s:-0}\" \"\${m:-0}\" \"\$n\"; done")
        }
        val r = exec(script)
        if (r.exitCode != 0) return emptyList()
        return r.stdout.toString(Charsets.UTF_8).lines()
            .filter { it.isNotBlank() }
            .mapNotNull { line ->
                val parts = line.split('\t')
                if (parts.size < 4) return@mapNotNull null
                val (type, size, mtime, name) = parts
                FileEntry(
                    name = name,
                    isDirectory = type == "dir",
                    size = size.toLongOrNull() ?: 0L,
                    lastModified = (mtime.toLongOrNull() ?: 0L) * 1000L,
                    localFile = null,
                    permissions = "rwx"
                )
            }
    }

    override fun listFilesRecursive(path: String, maxDepth: Int): List<String> {
        if (!isDirectory(path)) return emptyList()
        val dir = hostPath(path)
        val r = exec("find ${shQuote(dir)} -maxdepth $maxDepth -type f")
        if (r.exitCode != 0) return emptyList()
        val prefix = dir.trimEnd('/') + "/"
        return r.stdout.toString(Charsets.UTF_8).lines()
            .filter { it.isNotBlank() }
            .map { it.removePrefix(prefix) }
    }

    override fun readBytes(path: String): ByteArray {
        val p = hostPath(path)
        if (!exists(path)) throw NoSuchFileException(File(p))
        val r = exec("cat ${shQuote(p)}")
        if (r.exitCode != 0) throw NoSuchFileException(File(p))
        return r.stdout
    }

    override fun writeBytes(path: String, bytes: ByteArray, overwrite: Boolean) {
        val p = hostPath(path)
        if (exists(path) && !overwrite) throw FileAlreadyExistsException(File(p))
        writeBytesInternal(p, bytes)
    }

    override fun writeStream(path: String, input: InputStream, overwrite: Boolean): Long {
        val p = hostPath(path)
        if (exists(path) && !overwrite) throw FileAlreadyExistsException(File(p))
        val bytes = input.readBytes()
        writeBytesInternal(p, bytes)
        return bytes.size.toLong()
    }

    /** 父目录 → 通过 stdin 喂 `cat > target` 完成二进制落盘，避免命令行参数长度/转义问题。 */
    private fun writeBytesInternal(realPath: String, bytes: ByteArray) {
        val parent = File(realPath).parentFile?.absolutePath
        if (parent != null) execChecked("mkdir -p ${shQuote(parent)}")
        execChecked("cat > ${shQuote(realPath)}", bytes)
    }

    override fun copyToLocal(path: String): File {
        val p = hostPath(path)
        if (!exists(path)) throw NoSuchFileException(File(p))
        // root 目标 App 进程读不了，先经 root 复制到 App 私有缓存目录，再返回本地文件。
        val cacheDir = File(context.filesDir, "root_shell_cache").apply { mkdirs() }
        val local = File(cacheDir, "${System.currentTimeMillis()}_${File(p).name}")
        execChecked("cp ${shQuote(p)} ${shQuote(local.absolutePath)}")
        return local
    }

    override fun delete(path: String) {
        exec("rm -f ${shQuote(hostPath(path))}")
    }

    override fun deleteRecursively(path: String) {
        if (!exists(path)) return
        execChecked("rm -rf ${shQuote(hostPath(path))}")
    }

    override fun rename(path: String, newPath: String) {
        val src = hostPath(path)
        val dst = hostPath(newPath)
        if (!exists(path)) throw NoSuchFileException(File(src))
        if (exists(newPath)) throw FileAlreadyExistsException(File(dst))
        File(dst).parentFile?.let { parent -> execChecked("mkdir -p ${shQuote(parent.absolutePath)}") }
        execChecked("mv ${shQuote(src)} ${shQuote(dst)}")
    }

    override fun copy(path: String, newPath: String, overwrite: Boolean) {
        val src = hostPath(path)
        val dst = hostPath(newPath)
        if (!exists(path)) throw NoSuchFileException(File(src))
        if (exists(newPath)) {
            if (!overwrite) throw FileAlreadyExistsException(File(dst))
            execChecked("rm -rf ${shQuote(dst)}")
        }
        File(dst).parentFile?.let { parent -> execChecked("mkdir -p ${shQuote(parent.absolutePath)}") }
        execChecked("cp -r ${shQuote(src)} ${shQuote(dst)}")
    }

    override fun move(path: String, newPath: String, overwrite: Boolean) {
        val src = hostPath(path)
        val dst = hostPath(newPath)
        if (!exists(path)) throw NoSuchFileException(File(src))
        if (exists(newPath)) {
            if (!overwrite) throw FileAlreadyExistsException(File(dst))
            execChecked("rm -rf ${shQuote(dst)}")
        }
        File(dst).parentFile?.let { parent -> execChecked("mkdir -p ${shQuote(parent.absolutePath)}") }
        execChecked("mv ${shQuote(src)} ${shQuote(dst)}")
    }

    override fun mkdirs(path: String) {
        execChecked("mkdir -p ${shQuote(hostPath(path))}")
    }

    override fun parentPath(path: String): String? {
        val parent = host(path).parentFile ?: return null
        return pathMapper.toContainerPath(parent.absolutePath)
    }

    override fun toDisplayPath(path: String): String =
        pathMapper.toContainerPath(host(path).absolutePath)
}
