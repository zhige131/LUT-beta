package com.lutbeta.app.ftp

import org.apache.ftpserver.DataConnectionConfigurationFactory
import org.apache.ftpserver.FtpServer
import org.apache.ftpserver.FtpServerFactory
import org.apache.ftpserver.ftplet.Authentication
import org.apache.ftpserver.ftplet.DefaultFtplet
import org.apache.ftpserver.ftplet.FtpException
import org.apache.ftpserver.ftplet.FtpRequest
import org.apache.ftpserver.ftplet.FtpSession
import org.apache.ftpserver.ftplet.FtpletResult
import org.apache.ftpserver.ftplet.User
import org.apache.ftpserver.ftplet.UserManager
import org.apache.ftpserver.filesystem.nativefs.impl.NativeFtpFile
import org.apache.ftpserver.listener.ListenerFactory
import org.apache.ftpserver.usermanager.UsernamePasswordAuthentication
import org.apache.ftpserver.impl.DefaultConnectionConfig
import org.apache.ftpserver.usermanager.impl.BaseUser
import org.apache.ftpserver.usermanager.impl.ConcurrentLoginPermission
import org.apache.ftpserver.usermanager.impl.WritePermission
import java.io.File

/**
 * Minimal, write-only FTP server tuned for Sony's camera FTP client.
 * The camera uploads into the app's private incoming folder.
 */
class FtpServerManager(
    private val root: File,
    private val userName: String,
    private val password: String,
    private val port: Int,
    private val passiveAddress: String?,
    private val onUploadFinished: (File) -> Unit,
    private val onConnectionChanged: (Int) -> Unit = {}
) {
    private var server: FtpServer? = null
    private val clients = java.util.concurrent.atomic.AtomicInteger(0)

    val isRunning: Boolean get() = server != null

    @Throws(Exception::class)
    fun start() {
        if (server != null) return
        root.mkdirs()
        clients.set(0)

        val login = userName.ifBlank { "lutbeta" }
        val secret = password
        val home = root.absolutePath
        val ftpUser = BaseUser().apply {
            name = login
            setPassword(secret)
            homeDirectory = home
            authorities = listOf(
                WritePermission(),
                ConcurrentLoginPermission(10, 10)
            )
            setEnabled(true)
            maxIdleTime = 900
        }

        val listenPort = port
        val advertisedAddress = passiveAddress
        val factory = FtpServerFactory()
        val listenerFactory = ListenerFactory().apply {
            setPort(listenPort)
            setIdleTimeout(900)
        }
        val dataConfig = DataConnectionConfigurationFactory().apply {
            passivePorts = "50000-50020"
            if (!advertisedAddress.isNullOrBlank()) {
                passiveExternalAddress = advertisedAddress
            }
            idleTime = 300
        }
        listenerFactory.dataConnectionConfiguration = dataConfig.createDataConnectionConfiguration()
        factory.addListener("default", listenerFactory.createListener())
        factory.userManager = SingleUserManager(ftpUser, secret)
        factory.connectionConfig = DefaultConnectionConfig(
            false, // anonymous logins
            1000, // delay after a failed login
            0, // max concurrent logins, 0 = unlimited
            0, // max anonymous logins
            5, // max login failures
            0 // max threads, 0 = default
        )
        factory.ftplets = mapOf("lut-beta-upload" to UploadFtplet())

        val newServer = factory.createServer()
        newServer.start()
        server = newServer
    }

    fun stop() {
        runCatching { server?.stop() }
        server = null
        clients.set(0)
        onConnectionChanged(0)
    }

    private fun resolve(argument: String): File? {
        val relative = argument.replace('\\', '/').trimStart('/')
        if (relative.isBlank()) return null
        val file = File(root, relative)
        return try {
            val canonicalRoot = root.canonicalPath + File.separator
            if (file.canonicalPath.startsWith(canonicalRoot)) file else null
        } catch (e: Exception) {
            null
        }
    }

    private inner class UploadFtplet : DefaultFtplet() {
        override fun onConnect(session: FtpSession): FtpletResult {
            onConnectionChanged(clients.incrementAndGet())
            return FtpletResult.DEFAULT
        }

        override fun onDisconnect(session: FtpSession): FtpletResult {
            onConnectionChanged(clients.updateAndGet { current -> (current - 1).coerceAtLeast(0) })
            return FtpletResult.DEFAULT
        }

        override fun onUploadEnd(session: FtpSession, request: FtpRequest): FtpletResult =
            handleUpload(session, request)

        override fun onAppendEnd(session: FtpSession, request: FtpRequest): FtpletResult =
            handleUpload(session, request)

        override fun onUploadUniqueEnd(session: FtpSession, request: FtpRequest): FtpletResult =
            handleUpload(session, request)

        private fun handleUpload(session: FtpSession, request: FtpRequest): FtpletResult {
            val file = runCatching {
                (session.fileSystemView.getFile(request.argument) as? NativeFtpFile)?.physicalFile
            }.getOrNull() ?: resolve(request.argument)
            if (file != null && isImage(file.name)) {
                onUploadFinished(file)
            }
            return FtpletResult.DEFAULT
        }
    }

    private class SingleUserManager(
        private val user: BaseUser,
        private val password: String
    ) : UserManager {
        override fun getUserByName(login: String?): User? = if (login == user.name) user else null

        override fun getAllUserNames(): Array<String> = arrayOf(user.name)

        override fun delete(login: String?) = Unit

        override fun save(user: User?) = Unit

        override fun doesExist(login: String?): Boolean = login == user.name

        @Throws(FtpException::class)
        override fun authenticate(authentication: Authentication?): User? {
            val auth = authentication as? UsernamePasswordAuthentication ?: return null
            return if (auth.username == user.name && auth.password == password) user else null
        }

        override fun getAdminName(): String = user.name

        override fun isAdmin(login: String?): Boolean = login == user.name
    }

    companion object {
        fun isImage(name: String): Boolean {
            val lower = name.lowercase()
            return lower.endsWith(".jpg") || lower.endsWith(".jpeg") ||
                lower.endsWith(".heic") || lower.endsWith(".heif")
        }
    }
}
