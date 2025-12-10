package com.xayah.core.network.client

import android.content.Context
import com.google.android.gms.auth.api.signin.GoogleSignIn
import com.google.android.gms.auth.api.signin.GoogleSignInAccount
import com.google.android.gms.common.api.Scope
import com.google.api.client.googleapis.extensions.android.gms.auth.GoogleAccountCredential
import com.google.api.client.http.FileContent
import com.google.api.client.http.javanet.NetHttpTransport
import com.google.api.client.json.gson.GsonFactory
import com.google.api.services.drive.Drive
import com.google.api.services.drive.DriveScopes
import com.xayah.core.model.database.CloudEntity
import com.xayah.core.model.database.GoogleDriveExtra
import com.xayah.core.network.util.getExtraEntity
import com.xayah.core.rootservice.parcelables.PathParcelable
import com.xayah.core.util.LogUtil
import com.xayah.core.util.GsonUtil
import com.xayah.core.util.PathUtil
import com.xayah.libpickyou.parcelables.DirChildrenParcelable
import java.io.File
import java.io.FileOutputStream

class GoogleDriveClientImpl(
    private val context: Context,
    private val entity: CloudEntity,
    private val extra: GoogleDriveExtra
) : CloudClient {
    private var driveService: Drive? = null
    private var rootFolderId: String = extra.folderId
    
    companion object {
        private const val TAG = "GoogleDriveClientImpl"
        const val MIME_TYPE_FOLDER = "application/vnd.google-apps.folder"
    }

    private fun log(msg: () -> String): String = run {
        LogUtil.log { TAG to msg() }
        msg()
    }

    override fun connect() {
        try {
            val account = GoogleSignIn.getLastSignedInAccount(context)
            if (account == null) {
                throw IllegalStateException("No Google account signed in")
            }

            if (account.email != extra.accountEmail && extra.accountEmail.isNotEmpty()) {
                throw IllegalStateException("Signed-in account (${account.email}) does not match configured account (${extra.accountEmail})")
            }

            val credential = GoogleAccountCredential.usingOAuth2(
                context,
                listOf(DriveScopes.DRIVE_FILE, DriveScopes.DRIVE_APPDATA)
            )
            credential.selectedAccount = account.account

            driveService = Drive.Builder(
                NetHttpTransport(),
                GsonFactory.getDefaultInstance(),
                credential
            )
                .setApplicationName("DataBackup")
                .build()

            val query = "name='DataBackup' and mimeType='${MIME_TYPE_FOLDER}' and 'root' in parents and trashed=false"
            val result = driveService?.files()?.list()
                ?.setQ(query)
                ?.setSpaces("drive")
                ?.setFields("files(id, name)")
                ?.execute()

            rootFolderId = if (result?.files?.isNotEmpty() == true) {
                log { "Found existing DataBackup folder: ${result.files[0].id}" }
                result.files[0].id
            } else {
                val newId = createFolder("DataBackup", "root")
                log { "Created new DataBackup folder: $newId" }
                newId
            }
        } catch (e: Exception) {
            log { "Failed to connect: ${e.message}" }
            throw e
        }
    }

    override fun disconnect() {
        driveService = null
        log { "Disconnected from Google Drive" }
    }

    private fun createFolder(name: String, parentId: String): String {
        val fileMetadata = com.google.api.services.drive.model.File().apply {
            this.name = name
            this.mimeType = MIME_TYPE_FOLDER
            this.parents = listOf(parentId)
        }

        val file = driveService?.files()?.create(fileMetadata)
            ?.setFields("id")
            ?.execute()
            ?: throw Exception("Failed to create folder: $name")

        return file.id
    }

    private fun getOrCreateFolder(path: String, parentId: String = rootFolderId): String {
        val cleanPath = path.trim('/').replace("//", "/")
        if (cleanPath.isEmpty()) return parentId

        val parts = cleanPath.split("/")
        var currentParentId = parentId

        for (part in parts) {
            if (part.isEmpty()) continue

            val query = "name='$part' and '$currentParentId' in parents and mimeType='$MIME_TYPE_FOLDER' and trashed=false"
            val result = driveService?.files()?.list()
                ?.setQ(query)
                ?.setSpaces("drive")
                ?.setFields("files(id, name)")
                ?.execute()

            currentParentId = if (result?.files.isNullOrEmpty()) {
                createFolder(part, currentParentId)
            } else {
                result!!.files[0].id
            }
        }

        return currentParentId
    }

    override fun mkdir(dst: String) {
        val parts = dst.trim('/').split("/")
        val folderName = parts.last()
        val parentPath = parts.dropLast(1).joinToString("/")
        val parentId = getOrCreateFolder(parentPath)
        createFolder(folderName, parentId)
    }

    override fun mkdirRecursively(dst: String) {
        getOrCreateFolder(dst)
    }

    override fun renameTo(src: String, dst: String) {
        val fileId = getFileId(src, isFolder = false) ?: throw Exception("File not found: $src")
        
        val dstParts = dst.trim('/').split("/")
        val newName = dstParts.last()
        val newParentPath = dstParts.dropLast(1).joinToString("/")
        val newParentId = getOrCreateFolder(newParentPath)

        val fileMetadata = com.google.api.services.drive.model.File().apply {
            name = newName
        }

        driveService?.files()?.update(fileId, fileMetadata)
            ?.setAddParents(newParentId)
            ?.setRemoveParents(getFileParentId(fileId) ?: "")
            ?.setFields("id, parents")
            ?.execute()
    }

    override fun upload(src: String, dst: String, onUploading: (read: Long, total: Long) -> Unit) {
        val file = File(src)
        if (!file.exists()) {
            throw Exception("Source file does not exist: $src")
        }

        val fileName = PathUtil.getFileName(src)
        val fullDstPath = if (dst.isEmpty()) fileName else "$dst/$fileName"
        
        val dstParts = fullDstPath.trim('/').split("/")
        val actualFileName = dstParts.last()
        val parentPath = dstParts.dropLast(1).joinToString("/")
        val parentId = getOrCreateFolder(parentPath)

        val fileMetadata = com.google.api.services.drive.model.File().apply {
            name = actualFileName
            parents = listOf(parentId)
        }

        val mediaContent = FileContent(null, file)
        
        driveService?.files()?.create(fileMetadata, mediaContent)
            ?.setFields("id")
            ?.execute()
            ?: throw Exception("Failed to upload file: $src")
        
        val fileSize = file.length()
        onUploading(fileSize, fileSize)
        
        log { "Uploaded: $src -> $fullDstPath" }
    }

    override fun download(src: String, dst: String, onDownloading: (written: Long, total: Long) -> Unit) {
        val fileName = PathUtil.getFileName(src)
        val folderPath = PathUtil.getParentPath(src)
        
        log { "Download requested: src='$src', dst='$dst'" }
        log { "Parsed: fileName='$fileName', folderPath='$folderPath'" }

        val folderId = if (folderPath.isEmpty()) {
            log { "Using rootFolderId: $rootFolderId" }
            rootFolderId
        } else {
            log { "Searching for folder: '$folderPath'" }
            val id = getFileId(folderPath, isFolder = true)
            if (id == null) {
                log { "ERROR: Folder not found: '$folderPath'" }
                throw Exception("Folder not found: $folderPath")
            }
            log { "Found folderId: $id" }
            id
        }
        
        val query = "name='$fileName' and '$folderId' in parents and trashed=false"
        log { "Searching file with query: $query" }
        
        val result = driveService?.files()?.list()
            ?.setQ(query)
            ?.setSpaces("drive")
            ?.setFields("files(id, name, mimeType, size)")
            ?.execute()
        
        val fileId = result?.files?.firstOrNull()?.id 
        if (fileId == null) {
            log { "ERROR: File not found: '$fileName' in folder '$folderId'" }
            log { "Folder contents: ${result?.files?.map { "${it.name} (${it.mimeType})" }}" }
            throw Exception("File not found: $src")
        }
        log { "Found fileId: $fileId" }
        
        val fullDstPath = "$dst/$fileName"
        
        val outputFile = File(fullDstPath)
        outputFile.parentFile?.mkdirs()

        val outputStream = FileOutputStream(outputFile)
        driveService?.files()?.get(fileId)?.executeMediaAndDownloadTo(outputStream)
        outputStream.close()

        val fileSize = outputFile.length()
        onDownloading(fileSize, fileSize)
        
        log { "Downloaded: $src -> $fullDstPath" }
    }

    override fun deleteFile(src: String) {
        val fileId = getFileId(src, isFolder = false) ?: return
        driveService?.files()?.delete(fileId)?.execute()
        log { "Deleted file: $src" }
    }

    override fun removeDirectory(src: String) {
        deleteFile(src) 
    }

    override fun clearEmptyDirectoriesRecursively(src: String) {
        log { "clearEmptyDirectoriesRecursively not implemented for Google Drive" }
    }

    override fun deleteRecursively(src: String) {
        val folderId = getFileId(src, isFolder = true) ?: return
        
        val query = "'$folderId' in parents and trashed=false"
        val result = driveService?.files()?.list()
            ?.setQ(query)
            ?.setFields("files(id, mimeType)")
            ?.execute()

        result?.files?.forEach { file ->
            if (file.mimeType == MIME_TYPE_FOLDER) {
                deleteRecursively("${src}/${file.name}")
            } else {
                driveService?.files()?.delete(file.id)?.execute()
            }
        }

        driveService?.files()?.delete(folderId)?.execute()
        log { "Deleted recursively: $src" }
    }

    override fun listFiles(src: String): DirChildrenParcelable {
        val folderId = if (src.isEmpty() || src == "/") rootFolderId else getFileId(src, isFolder = true)
        if (folderId == null) {
            return DirChildrenParcelable(files = emptyList(), directories = emptyList())
        }

        val query = "'$folderId' in parents and trashed=false"
        val result = driveService?.files()?.list()
            ?.setQ(query)
            ?.setFields("files(id, name, mimeType, size, modifiedTime)")
            ?.execute()

        val files = mutableListOf<com.xayah.libpickyou.parcelables.FileParcelable>()
        val directories = mutableListOf<com.xayah.libpickyou.parcelables.FileParcelable>()
        
        result?.files?.forEach { file ->
            val creationTime = file.modifiedTime?.value ?: 0L
            val fileParcelable = com.xayah.libpickyou.parcelables.FileParcelable(file.name, creationTime)
            if (file.mimeType == MIME_TYPE_FOLDER) {
                directories.add(fileParcelable)
            } else {
                files.add(fileParcelable)
            }
        }

        return DirChildrenParcelable(files = files, directories = directories)
    }

    override fun walkFileTree(src: String): List<PathParcelable> {
        val result = mutableListOf<PathParcelable>()
        
        fun walk(path: String, folderId: String) {
            val query = "'$folderId' in parents and trashed=false"
            val files = driveService?.files()?.list()
                ?.setQ(query)
                ?.setFields("files(id, name, mimeType)")
                ?.execute()

            files?.files?.forEach { file ->
                val fullPath = "${path.trimEnd('/')}/${file.name}"
                result.add(PathParcelable(fullPath))
                
                if (file.mimeType == MIME_TYPE_FOLDER) {
                    walk(fullPath, file.id)
                }
            }
        }

        val startFolderId = if (src.isEmpty() || src == "/") rootFolderId else getFileId(src, isFolder = true)
        if (startFolderId != null) {
            walk(src, startFolderId)
        }

        return result
    }

    override fun exists(src: String): Boolean {
        return getFileId(src, isFolder = false) != null
    }

    override fun size(src: String): Long {
        val fileId = getFileId(src, isFolder = false) ?: return 0
        val file = driveService?.files()?.get(fileId)
            ?.setFields("size")
            ?.execute()
        return file?.getSize() ?: 0
    }

    override suspend fun testConnection() {
        try {
            connect()
            driveService?.files()?.get(rootFolderId)?.execute()
            disconnect()
        } catch (e: Exception) {
            log { "Connection test failed: ${e.message}" }
            throw e
        }
    }

    private fun getFileId(path: String, isFolder: Boolean = false): String? {
        if (path.isEmpty() || path == "/") return rootFolderId

        val parts = path.trim('/').split("/")
        var currentParentId = rootFolderId

        parts.forEachIndexed { index, part ->
            if (part.isEmpty()) return@forEachIndexed

            val isLastPart = index == parts.size - 1
            val checkIsFolder = !isLastPart || isFolder
            
            val query = if (checkIsFolder) {
                "name='$part' and '$currentParentId' in parents and mimeType='$MIME_TYPE_FOLDER' and trashed=false"
            } else {
                "name='$part' and '$currentParentId' in parents and trashed=false"
            }
            
            val result = driveService?.files()?.list()
                ?.setQ(query)
                ?.setSpaces("drive")
                ?.setFields("files(id)")
                ?.execute()

            if (result?.files.isNullOrEmpty()) {
                return null
            }
            currentParentId = result?.files?.firstOrNull()?.id ?: return null
        }

        return currentParentId
    }

    private fun getOrCreateFolder(path: String): String {
        val existingId = getFileId(path, isFolder = true)
        if (existingId != null) return existingId

        val parts = path.trim('/').split("/")
        var currentParentId = rootFolderId
        var currentPath = ""

        for (part in parts) {
            if (part.isEmpty()) continue
            
            currentPath = if (currentPath.isEmpty()) part else "$currentPath/$part"
            val folderId = getFileId(currentPath, isFolder = true)
            
            if (folderId != null) {
                currentParentId = folderId
            } else {
                currentParentId = createFolder(part, currentParentId)
            }
        }
        return currentParentId
    }

    private fun getFileParentId(fileId: String): String? {
        val file = driveService?.files()?.get(fileId)
            ?.setFields("parents")
            ?.execute()
        return file?.parents?.firstOrNull()
    }

    override suspend fun setRemote(context: Context, onSet: suspend (remote: String, extra: String) -> Unit) {
        val updatedExtra = GoogleDriveExtra(
            accountEmail = extra.accountEmail,
            folderId = rootFolderId  
        )
        onSet("", GsonUtil().toJson(updatedExtra))
    }
}
