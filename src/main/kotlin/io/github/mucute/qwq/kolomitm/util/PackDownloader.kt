package io.github.mucute.qwq.kolomitm.util

import com.google.gson.JsonParser
import io.netty.buffer.ByteBuf
import io.netty.buffer.Unpooled
import java.io.IOException
import java.lang.AutoCloseable
import java.net.MalformedURLException
import java.net.URI
import java.net.URISyntaxException
import java.net.URL
import java.nio.channels.FileChannel
import java.nio.charset.StandardCharsets
import java.nio.file.*
import java.security.InvalidAlgorithmParameterException
import java.security.InvalidKeyException
import java.security.NoSuchAlgorithmException
import java.util.*
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.function.Consumer
import javax.crypto.BadPaddingException
import javax.crypto.Cipher
import javax.crypto.IllegalBlockSizeException
import javax.crypto.NoSuchPaddingException
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec
import kotlin.math.max

class PackDownloader(private val packsPath: Path) : AutoCloseable {
    val packs: MutableMap<UUID, Pack>
    private val executor: ExecutorService

    init {
        try {
            Files.createDirectories(this.packsPath)
        } catch (e: IOException) {
            throw RuntimeException("Failed to create packs directory", e)
        }

        this.packs = Collections.synchronizedMap<UUID, Pack>(HashMap())

        val availableProcessors = Runtime.getRuntime().availableProcessors()
        this.executor = Executors.newFixedThreadPool(
            max(2, availableProcessors / 2)
        )
    }

    class Pack internal constructor(private val packId: UUID, contentKey: String?, packsPath: Path, cdnUrl: String?) {
        private val chunks: SortedMap<Int?, ByteBuf> = TreeMap<Int?, ByteBuf>()
        private val contentKey: ByteArray? = contentKey?.toByteArray()
        private var cdnUrl: URL? = null
        private val packPath = packsPath.resolve(this.packId.toString() + ".zip")

        init {
            try {
                this.cdnUrl = if (cdnUrl != null && !cdnUrl.isEmpty()) URI(cdnUrl).toURL() else null
            } catch (_: MalformedURLException) {
                this.cdnUrl = null
            } catch (_: URISyntaxException) {
                this.cdnUrl = null
            }
        }

        fun addChunk(offset: Int, chunk: ByteBuf?) {
            chunks[offset] = chunk
        }

        fun process() {
            if (this.chunks.isEmpty() && this.cdnUrl == null) return
            this.writeStream()
            this.decryptStream()
        }

        private fun writeStream() {
            if (this.cdnUrl != null) {
                try {
                    this.cdnUrl!!.openStream().use { inputStream ->
                        Files.copy(inputStream, this.packPath, StandardCopyOption.REPLACE_EXISTING)
                    }
                } catch (e: IOException) {
                    System.err.println("Failed to download pack " + this.packId + " from CDN " + this.cdnUrl + ": " + e)
                }
                return
            }
            try {
                FileChannel.open(
                    packPath,
                    StandardOpenOption.WRITE, StandardOpenOption.CREATE,
                    StandardOpenOption.TRUNCATE_EXISTING
                ).use { channel ->
                    for (buf in chunks.values) {
                        buf.readBytes(channel, buf.readableBytes())
                    }
                }
            } catch (e: IOException) {
                System.err.println("Failed to write pack " + this.packId + " to file system: " + e)
            } finally {
                chunks.values.forEach(Consumer { obj: ByteBuf? -> obj!!.release() })
            }
        }

        private fun decryptStream() {
            try {
                this.orientFileSystem().use { fs ->
                    this.decryptFileSystem(fs)
                }
            } catch (e: IOException) {
                System.err.println("Failed to process pack " + this.packId + ": " + e.message)
            } catch (e: NoSuchAlgorithmException) {
                System.err.println("Failed to process pack " + this.packId + ": " + e.message)
            } catch (e: NoSuchPaddingException) {
                System.err.println("Failed to process pack " + this.packId + ": " + e.message)
            } catch (e: InvalidKeyException) {
                System.err.println("Failed to process pack " + this.packId + ": " + e.message)
            } catch (e: InvalidAlgorithmParameterException) {
                System.err.println("Failed to process pack " + this.packId + ": " + e.message)
            } catch (e: IllegalBlockSizeException) {
                System.err.println("Failed to process pack " + this.packId + ": " + e.message)
            } catch (e: BadPaddingException) {
                System.err.println("Failed to process pack " + this.packId + ": " + e.message)
            }
        }

        @Throws(IOException::class)
        private fun orientFileSystem(): FileSystem {
            var fs = FileSystems.newFileSystem(this.packPath, mutableMapOf<String?, Any?>(), null)
            if (!this.containsManifest(fs) && this.cdnUrl != null) {
                for (path in fs.rootDirectories) {
                    if (!path.endsWith((".zip"))) continue
                    Files.copy(path, this.packPath, StandardCopyOption.REPLACE_EXISTING)
                    fs.close()
                    fs = FileSystems.newFileSystem(this.packPath, mutableMapOf<String?, Any?>(), null)
                    break
                }
            }

            if (this.containsManifest(fs)) {
                return fs
            }

            var contentDir: Path? = null
            Files.newDirectoryStream(fs.getPath("/")).use { stream ->
                for (entry in stream) {
                    if (Files.isDirectory(entry)) {
                        contentDir = entry
                        break
                    }
                }
            }
            if (contentDir != null) {
                this.moveDirectoryContents(contentDir, fs.getPath("/"))
                Files.delete(contentDir)
            }

            return fs
        }

        private fun containsManifest(fs: FileSystem): Boolean {
            return Files.exists(fs.getPath("/manifest.json")) || Files.exists(fs.getPath("/pack_manifest.json"))
        }

        @Throws(IOException::class)
        private fun moveDirectoryContents(source: Path, destination: Path) {
            val pathsToMove: MutableList<Path> = ArrayList<Path>()
            Files.newDirectoryStream(source).use { stream ->
                for (path in stream) {
                    pathsToMove.add(path)
                }
            }
            for (path in pathsToMove) {
                val targetPath = destination.resolve(path.fileName.toString())
                if (Files.isDirectory(path)) {
                    Files.createDirectory(targetPath)
                    moveDirectoryContents(path, targetPath)
                    Files.delete(path)
                } else {
                    Files.move(path, targetPath, StandardCopyOption.REPLACE_EXISTING)
                }
            }
        }

        @Throws(
            NoSuchAlgorithmException::class,
            NoSuchPaddingException::class,
            InvalidKeyException::class,
            InvalidAlgorithmParameterException::class,
            IOException::class,
            IllegalBlockSizeException::class,
            BadPaddingException::class
        )
        private fun decryptFileSystem(fs: FileSystem) {
            if (this.contentKey == null || this.contentKey.isEmpty() || !Files.exists(fs.getPath("/contents.json"))) return

            val cipher = Cipher.getInstance("AES/CFB8/NoPadding")
            cipher.init(
                Cipher.DECRYPT_MODE, SecretKeySpec(this.contentKey, "AES"),
                IvParameterSpec(this.contentKey.copyOfRange(0, 16))
            )
            val contents = Unpooled.wrappedBuffer(Files.readAllBytes(fs.getPath("/contents.json")))
            contents.readerIndex(256)
            val encryptedContents = ByteArray(contents.readableBytes())
            contents.readBytes(encryptedContents)
            Files.write(fs.getPath("/contents.json"), cipher.doFinal(encryptedContents))
            val contentsArray = JsonParser.parseString(Files.readString(fs.getPath("/contents.json")))
                .asJsonObject.getAsJsonArray("content")

            for (element in contentsArray) {
                val contentItem = element.asJsonObject
                if (!contentItem.has("key") || contentItem.get("key").isJsonNull) continue
                val key = contentItem.get("key").asString
                val path = contentItem.get("path").asString
                val filePath = fs.getPath("/", path)
                if (!Files.exists(filePath)) {
                    continue
                }
                if (mutableListOf<String?>(
                        "manifest.json",
                        "pack_manifest.json",
                        "pack_icon.png",
                        "README.txt"
                    ).contains(path)
                ) {
                    continue
                }
                val encryptedData = Files.readAllBytes(filePath)
                val keyBytes = key.toByteArray(StandardCharsets.ISO_8859_1)
                cipher.init(
                    Cipher.DECRYPT_MODE, SecretKeySpec(keyBytes, "AES"),
                    IvParameterSpec(keyBytes.copyOfRange(0, 16))
                )
                Files.write(filePath, cipher.doFinal(encryptedData))
            }
        }
    }

    fun registerPack(packId: UUID, cdnUrl: String?, contentKey: String?) {
        packs[packId] = Pack(packId, contentKey, this@PackDownloader.packsPath, cdnUrl)
    }

    fun addChunk(packId: UUID?, offset: Int, chunk: ByteBuf?) {
        val pack = packs[packId] ?: return
        pack.addChunk(offset, chunk)
    }

    fun processPacks() {
        for (pack in packs.values) {
            executor.submit { pack.process() }
        }
        packs.clear()
    }

    override fun close() {
        executor.shutdown()
        try {
            if (!executor.awaitTermination(60, TimeUnit.SECONDS)) {
                executor.shutdownNow()
                if (!executor.awaitTermination(60, TimeUnit.SECONDS)) {
                    System.err.println("Executor did not terminate")
                }
            }
        } catch (_: InterruptedException) {
            executor.shutdownNow()
            Thread.currentThread().interrupt()
        }
    }
}