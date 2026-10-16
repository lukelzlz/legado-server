package io.legado.server

import java.io.OutputStream
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.util.UUID

/**
 * 两级散列目录下的**原子落盘**存储：`<root>/<key[0:2]>/<key>`。
 *
 * ## 为什么必须原子（与 WebDAV 的 PUT 同款手法）
 * 先写同目录 `tmp_*.part` 再 `ATOMIC_MOVE`。否则进程被杀 / 网络中断时，磁盘上会留下
 * **半截文件**，而读方无法分辨「这是完整图片」还是「下载了一半」——表现为裂图或
 * 播放中途断流，且**极难复现与排查**。
 *
 * ## 为什么路径只用哈希
 * AGENTS.md 铁律：`Path.resolve()` 对非法字符（书名里的换行、`/`、`:` …）**会抛异常**，
 * 不是返回一个不存在的路径。因此本存储**只接受十六进制 key**，绝不用用户数据拼路径。
 *
 * ## 漫画与有声书共用
 * 图片（`ImageCache`）与音频（`AudioCache`）都走这里，避免两套落盘代码各自漂移。
 */
class AtomicFileStore(private val root: Path) {

    private val keyPattern = Regex("[0-9a-f]{64}")

    init { Files.createDirectories(root) }

    /** 合规 key（64 位小写 hex）才允许映射到路径；非法一律抛错，绝不"猜一个路径"。 */
    fun pathFor(key: String): Path {
        require(keyPattern.matches(key)) { "非法缓存 key（必须是 64 位小写十六进制）" }
        return root.resolve(key.substring(0, 2)).resolve(key)
    }

    fun exists(key: String): Boolean = runCatching { Files.exists(pathFor(key)) }.getOrDefault(false)

    fun size(key: String): Long? = runCatching {
        val path = pathFor(key)
        if (Files.exists(path)) Files.size(path) else null
    }.getOrNull()

    fun read(key: String): ByteArray? = runCatching {
        val path = pathFor(key)
        if (Files.exists(path)) Files.readAllBytes(path) else null
    }.getOrNull()

    fun delete(key: String): Boolean = runCatching { Files.deleteIfExists(pathFor(key)) }.getOrDefault(false)

    /** 一次性写入（图片/封面这类小字节）。返回最终文件路径。 */
    fun write(key: String, bytes: ByteArray): Path {
        val target = pathFor(key)
        Files.createDirectories(target.parent)
        val temp = target.resolveSibling("tmp_${UUID.randomUUID()}.part")
        try {
            Files.write(temp, bytes)
            Files.move(temp, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
        } catch (error: Throwable) {
            runCatching { Files.deleteIfExists(temp) }
            throw error
        }
        return target
    }

    /**
     * 流式写入（音频这类大字节：10~60 MiB/章）。
     *
     * ⚠️ **严禁**把大文件先 `readAllBytes()` 再 `write()`——那会直接吃掉几百 MB 堆。
     * 返回实际写入字节数；失败时清理临时文件并抛错（调用方负责静默降级）。
     */
    fun writeStream(key: String, block: (OutputStream) -> Unit): Long {
        val target = pathFor(key)
        Files.createDirectories(target.parent)
        val temp = target.resolveSibling("tmp_${UUID.randomUUID()}.part")
        var written = 0L
        try {
            Files.newOutputStream(temp).use { output ->
                val counting = object : OutputStream() {
                    override fun write(b: Int) { output.write(b); written++ }
                    override fun write(b: ByteArray, off: Int, len: Int) { output.write(b, off, len); written += len }
                    override fun flush() = output.flush()
                }
                block(counting)
                counting.flush()
            }
            Files.move(temp, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
        } catch (error: Throwable) {
            runCatching { Files.deleteIfExists(temp) }
            throw error
        }
        return written
    }

    /** 扫描根目录下所有已落盘的 key（GC 用；跳过 `*.part` 与非法名）。 */
    fun listKeys(): Sequence<String> = runCatching {
        Files.list(root).use { shards ->
            shards.filter { Files.isDirectory(it) }.flatMap { shard ->
                Files.list(shard).use { files ->
                    files.map { it.fileName.toString() }
                        .filter { keyPattern.matches(it) }
                        .toList()
                        .stream()
                }
            }.toList().asSequence()
        }
    }.getOrDefault(emptySequence())

    /** 目录总占用字节（GC 配额判定用；跳过 `.part`）。 */
    fun totalBytes(): Long = runCatching {
        Files.walk(root).use { paths ->
            paths.filter { Files.isRegularFile(it) && !it.fileName.toString().endsWith(".part") }
                .mapToLong { runCatching { Files.size(it) }.getOrDefault(0L) }
                .sum()
        }
    }.getOrDefault(0L)
}
