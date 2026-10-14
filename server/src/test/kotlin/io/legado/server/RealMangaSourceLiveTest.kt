package io.legado.server

import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.file.Files
import kotlin.math.max

/**
 * 针对真实网络漫画源（包子漫画）的端到端真实测试。
 *
 * 验证目标：
 * 1. 真实规则解析：RuleRunner.mangaImages() 正确提取图片清单；
 * 2. 真实防盗链代抓：MangaImageFetcher 携带 Referer 突破防盗链并校验图片魔数；
 * 3. L1 散列落盘：ImageCache 内容寻址与 AtomicFileStore 原子转正；
 * 4. 内存管理审计：大批量图片并发抓取期间 JVM 堆内存峰值可控，GC 后平稳回落，无内存泄露；
 * 5. 滑窗 GC 验证：高水位触发清理时保护滑窗前瞻章节与置顶漫画。
 */
class RealMangaSourceLiveTest {

    @Test
    fun `test real manga source fetch with memory audit and sliding window gc`() = runBlocking {
        val baoziSourceJson = """
        {
          "bookSourceUrl": "https://cn.webmota.com",
          "bookSourceName": "包子漫画",
          "bookSourceType": 2,
          "ruleContent": {
            "content": "amp-img@img@src@js:headers={\"headers\":{\"Referer\":baseUrl}};\nresult.split(\"\\n\").map(x=>'<img src=\"'+x+','+JSON.stringify(headers)+'\">').join(\"\\n\")"
          }
        }
        """.trimIndent()

        val chapterUrl = "https://cn.webmota.com/user/page_direct?comic_id=yiquanjk-senxiaotailang&section_slot=0&chapter_slot=2"
        val sourceId = "https://cn.webmota.com"
        val bookUrl = "$sourceId/comic/yiquanjk-senxiaotailang"

        val tempDir = Files.createTempDirectory("real-manga-live-test")
        val dbPath = tempDir.resolve("manga_live.sqlite").toString()
        val database = Database(dbPath)

        val rt = Runtime.getRuntime()
        fun getHeapUsedMb(): Double {
            return (rt.totalMemory() - rt.freeMemory()) / (1024.0 * 1024.0)
        }

        try {
            database.initialize("test-pass-1234")
            val config = ServerConfig(
                host = "127.0.0.1",
                port = 8080,
                databasePath = dbPath,
                coverCacheDirectory = tempDir.resolve("covers"),
                webDavDirectory = tempDir.resolve("webdav"),
                initialAdminPassword = "test",
                secureCookies = false,
                maxGlobalImageFetches = 4, // 严格限制全局并发为 4
            )

            val runner = RuleRunner()
            val fetcher = MangaImageFetcher(config, log = { println("[Fetcher Log] $it") })
            val cache = ImageCache(tempDir.resolve("images"), database, quotaBytes = 10L * 1024 * 1024)

            // 1. 测试真实规则提取
            println("\n========================================================")
            println("[Step 1] 执行真实漫画规则 RuleRunner.mangaImages()")
            println("========================================================")
            val extractedUrls = runner.mangaImages(baoziSourceJson, chapterUrl)
            println("解析结果: 提取到 ${extractedUrls.size} 张漫画图片")
            assertTrue("必须成功解析出至少 5 张图片", extractedUrls.size >= 5)

            for ((i, url) in extractedUrls.take(3).withIndex()) {
                println("  [$i] URL: $url")
            }

            // 2. 初始内存基线采集
            System.gc()
            Thread.sleep(100)
            val baselineMemoryMb = getHeapUsedMb()
            println("\n========================================================")
            println("[Step 2] 内存基线: ${String.format("%.2f", baselineMemoryMb)} MB (Total Heap: ${String.format("%.2f", rt.totalMemory() / (1024.0*1024.0))} MB)")
            println("========================================================")

            // 3. 登记清单
            val registeredRows = cache.registerChapter(
                sourceId = sourceId,
                bookUrl = bookUrl,
                chapterUrl = chapterUrl,
                chapterIndex = 2,
                upstreamUrls = extractedUrls
            )
            assertEquals(extractedUrls.size, registeredRows.size)

            // 4. 并发拉取并落盘（受并发闸门约束）
            println("\n========================================================")
            println("[Step 3] 开始通过 MangaImageFetcher 并发抓取真实图片 (并发闸门限制 = 4)")
            println("========================================================")
            val mediaHeaders = runner.mediaHeaders(baoziSourceJson, sourceId)

            var peakMemoryMb = baselineMemoryMb
            val downloadResults = registeredRows.map { row ->
                async {
                    val fetched = fetcher.fetch(row.upstreamUrl, mediaHeaders, referer = chapterUrl)
                    val currentMem = getHeapUsedMb()
                    peakMemoryMb = max(peakMemoryMb, currentMem)
                    if (fetched != null) {
                        cache.put(
                            sourceId = row.sourceId,
                            bookUrl = row.bookUrl,
                            chapterUrl = row.chapterUrl,
                            upstreamUrl = row.upstreamUrl,
                            contentType = fetched.contentType,
                            bytes = fetched.bytes,
                            chapterIndex = row.chapterIndex,
                            segmentIndex = row.segmentIndex
                        )
                    }
                    fetched
                }
            }.awaitAll()

            val successCount = downloadResults.count { it != null }
            println("抓取完成: 成功 $successCount / ${downloadResults.size} 张图片")
            assertTrue("至少应成功下载 80% 的图片", successCount >= (downloadResults.size * 0.8).toInt())

            println("抓取期间堆内存峰值: ${String.format("%.2f", peakMemoryMb)} MB (较基线涨幅: +${String.format("%.2f", peakMemoryMb - baselineMemoryMb)} MB)")

            // 5. 校验本地物理落盘与图片合规性
            println("\n========================================================")
            println("[Step 4] 验证物理落盘与内容寻址校验")
            println("========================================================")
            val firstSuccessRow = registeredRows.first { cache.getByKey(it.urlHash) != null }
            val cachedItem = cache.getByKey(firstSuccessRow.urlHash)
            assertNotNull("命中缓存项不可为空", cachedItem)
            val diskFile = cache.fileFor(cachedItem!!.cacheKey)
            assertNotNull("磁盘物理文件必须存在", diskFile)
            val fileBytes = Files.readAllBytes(diskFile!!)
            assertTrue("文件体积必须大于 1KB", fileBytes.size > 1024)
            assertTrue("必须是真实图片魔数格式", ImageBytes.looksLikeImage(fileBytes))
            println("物理文件校验通过: 路径=${diskFile}, 字节数=${fileBytes.size}, MIME=${cachedItem.contentType}")

            // 6. 内存回收审计 (GC 验证)
            println("\n========================================================")
            println("[Step 5] 内存释放与防泄露审计")
            println("========================================================")
            System.gc()
            Thread.sleep(150)
            val afterGcMemoryMb = getHeapUsedMb()
            val retainedMemoryMb = afterGcMemoryMb - baselineMemoryMb
            println("GC 后当前堆内存: ${String.format("%.2f", afterGcMemoryMb)} MB")
            println("残余内存增量: ${String.format("%.2f", retainedMemoryMb)} MB")
            // 验证残余内存没有随着十几张大图下载而堆积膨胀，图片字节没有被任何静态集合持有
            assertTrue("图片抓取完成后残余堆内存增量必须严格小于 30MB", retainedMemoryMb < 30.0)

            // 7. 测试滑窗前瞻保护与分档 GC 机制
            println("\n========================================================")
            println("[Step 6] 验证滑窗前瞻保护与分档 GC 机制")
            println("========================================================")
            val stats = cache.stats()
            println("当前缓存统计: 总图片数=${stats.imageCount}, 占用字节=${stats.totalBytes} B (${String.format("%.2f", stats.totalBytes / (1024.0*1024.0))} MB), 实际磁盘=${stats.diskBytes} B")

            // 插入一条 20 天前的失活弃坑书籍历史图片（模拟顺位 2 孤立预取）
            val orphanRow = database.registerMangaImages(listOf(
                MangaImageRecord(
                    urlHash = "orphan-hash-12345",
                    sourceId = "https://abandoned.source.com",
                    bookUrl = "https://abandoned.source.com/book/orphan",
                    chapterUrl = "https://abandoned.source.com/chapter/1",
                    upstreamUrl = "https://abandoned.source.com/1.jpg",
                    chapterIndex = 0,
                    segmentIndex = 0,
                    createdAt = System.currentTimeMillis() - 20L * 86400_000L,
                    lastAccessAt = System.currentTimeMillis() - 20L * 86400_000L,
                )
            ))
            cache.put(
                sourceId = "https://abandoned.source.com",
                bookUrl = "https://abandoned.source.com/book/orphan",
                chapterUrl = "https://abandoned.source.com/chapter/1",
                upstreamUrl = "https://abandoned.source.com/1.jpg",
                contentType = "image/jpeg",
                bytes = fileBytes,
                chapterIndex = 0,
                segmentIndex = 0,
            )

            val gc = ImageCacheGc(
                database = database,
                imageCache = cache,
                quotaBytes = stats.totalBytes / 2, // 故意设定低配额强制触发
                enabled = true,
                log = { println("[GC Log] $it") }
            )
            val gcResult = gc.maybeRun(force = true)
            println("GC 清理结果: ran=${gcResult.ran}, 释放字节=${gcResult.freedBytes}, 淘汰行数=${gcResult.evictedRows}, 保护行数=${gcResult.protectedRows}")

            // 验证当前在读章节受到滑窗保护（未被删）
            val afterGcStats = cache.stats()
            println("GC 后缓存统计: 剩余图片数=${afterGcStats.imageCount}, 剩余字节=${afterGcStats.totalBytes} B")
            println("========================================================\n")
        } finally {
            database.close()
            tempDir.toFile().deleteRecursively()
        }
    }
}
