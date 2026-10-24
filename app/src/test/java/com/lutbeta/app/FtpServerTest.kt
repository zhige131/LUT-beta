package com.lutbeta.app

import com.lutbeta.app.ftp.FtpServerManager
import org.apache.commons.net.ftp.FTP
import org.apache.commons.net.ftp.FTPClient
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.File
import java.nio.file.Files
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

class FtpServerTest {
    @Test
    fun acceptsCameraStylePassiveUpload() {
        val root = Files.createTempDirectory("lutbeta-ftp").toFile()
        val uploaded = AtomicReference<File?>()
        val latch = CountDownLatch(1)
        val server = FtpServerManager(
            root = root,
            userName = "lutbeta",
            password = "a7c2",
            port = 21219,
            passiveAddress = "127.0.0.1",
            onUploadFinished = {
                uploaded.set(it)
                latch.countDown()
            }
        )

        server.start()
        assertTrue("server should report running", server.isRunning)
        Thread.sleep(800)
        try {
            val client = FTPClient()
            client.connect("127.0.0.1", 21219)
            assertTrue(client.login("lutbeta", "a7c2"))
            client.setFileType(FTP.BINARY_FILE_TYPE)
            client.enterLocalPassiveMode()

            val payload = byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 1, 2, 3, 4, 0xFF.toByte(), 0xD9.toByte())
            val stored = client.storeFile("DSC00001.JPG", ByteArrayInputStream(payload))
            assertTrue("FTP STOR failed: ${client.replyString}", stored)
            client.logout()
            client.disconnect()

            assertTrue("upload callback timed out", latch.await(3, TimeUnit.SECONDS))
            val file = uploaded.get()
            assertEquals("DSC00001.JPG", file?.name)
            assertTrue(file!!.exists())
            assertEquals(payload.size.toLong(), file.length())
        } finally {
            server.stop()
            root.deleteRecursively()
        }
    }

    @Test
    fun rejectsWrongPassword() {
        val root = Files.createTempDirectory("lutbeta-ftp-auth").toFile()
        val server = FtpServerManager(
            root = root,
            userName = "lutbeta",
            password = "a7c2",
            port = 21220,
            passiveAddress = "127.0.0.1",
            onUploadFinished = {}
        )
        server.start()
        Thread.sleep(800)
        try {
            val client = FTPClient()
            client.connect("127.0.0.1", 21220)
            assertFalse(client.login("lutbeta", "wrong"))
            client.disconnect()
        } finally {
            server.stop()
            root.deleteRecursively()
        }
    }
}
