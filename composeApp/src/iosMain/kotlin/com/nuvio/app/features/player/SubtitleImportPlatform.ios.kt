@file:OptIn(ExperimentalForeignApi::class)

package com.nuvio.app.features.player

import com.nuvio.app.features.autosync.SubtitleCharsetDetector
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.usePinned
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import platform.Foundation.NSData
import platform.Foundation.NSFileManager
import platform.Foundation.NSHomeDirectory
import platform.Foundation.NSURL
import platform.Foundation.dataWithContentsOfFile
import platform.UIKit.UIApplication
import platform.UIKit.UIDocumentPickerDelegateProtocol
import platform.UIKit.UIDocumentPickerMode
import platform.UIKit.UIDocumentPickerViewController
import platform.UIKit.UIViewController
import platform.UIKit.UIWindow
import platform.darwin.NSObject
import platform.posix.memcpy

/**
 * iOS external subtitle import. iOS has no synchronous file dialog, so [pickSubtitleFile]
 * presents the system Files picker and returns null; the chosen file is copied into the
 * app's own storage and handed to the player through [SubtitleImportBridge.onImport].
 */
internal actual object SubtitleImportPlatform {
    private val supportedExtensions = setOf("srt", "vtt", "ass", "ssa", "sub")

    // UIDocumentPickerViewController holds its delegate weakly; keep it alive here.
    private var activeDelegate: NSObject? = null

    actual fun pickSubtitleFile(): ImportedSubtitleFile? {
        val presenter = topViewController() ?: return null
        val delegate = object : NSObject(), UIDocumentPickerDelegateProtocol {
            override fun documentPicker(controller: UIDocumentPickerViewController, didPickDocumentsAtURLs: List<*>) {
                activeDelegate = null
                val url = didPickDocumentsAtURLs.firstOrNull() as? NSURL ?: return
                copyIntoAppStorage(url)?.let { file -> SubtitleImportBridge.onImport?.invoke(file) }
            }

            override fun documentPickerWasCancelled(controller: UIDocumentPickerViewController) {
                activeDelegate = null
            }
        }
        activeDelegate = delegate
        val picker = UIDocumentPickerViewController(
            // SRT/VTT/ASS/SSA have no single system UTI, so accept text and generic data and
            // validate the extension after picking.
            documentTypes = listOf("public.plain-text", "public.text", "public.data"),
            inMode = UIDocumentPickerMode.UIDocumentPickerModeImport,
        )
        picker.delegate = delegate
        picker.allowsMultipleSelection = false
        presenter.presentViewController(picker, true, null)
        return null
    }

    actual suspend fun readSubtitleText(path: String): String = withContext(Dispatchers.Default) {
        val filePath = if (path.startsWith("file:", ignoreCase = true)) NSURL(string = path).path ?: path else path
        val data = NSData.dataWithContentsOfFile(filePath) ?: error("Could not read subtitle file")
        SubtitleCharsetDetector.decode(data.toByteArray())
    }

    private fun copyIntoAppStorage(source: NSURL): ImportedSubtitleFile? {
        val name = source.lastPathComponent ?: return null
        if (name.substringAfterLast('.', "").lowercase() !in supportedExtensions) return null
        val accessed = source.startAccessingSecurityScopedResource()
        try {
            val directory = NSHomeDirectory() + "/Library/Caches/ImportedSubtitles"
            val fileManager = NSFileManager.defaultManager
            fileManager.createDirectoryAtPath(directory, withIntermediateDirectories = true, attributes = null, error = null)
            val destination = "$directory/$name"
            if (fileManager.fileExistsAtPath(destination)) fileManager.removeItemAtPath(destination, error = null)
            val sourcePath = source.path ?: return null
            if (!fileManager.copyItemAtPath(sourcePath, toPath = destination, error = null)) return null
            return ImportedSubtitleFile(path = destination, name = name)
        } finally {
            if (accessed) source.stopAccessingSecurityScopedResource()
        }
    }

    private fun topViewController(): UIViewController? {
        val application = UIApplication.sharedApplication
        val window = application.windows.firstOrNull { (it as? UIWindow)?.isKeyWindow() == true } as? UIWindow
            ?: application.windows.firstOrNull() as? UIWindow
        var controller = window?.rootViewController ?: return null
        while (true) {
            controller = controller.presentedViewController ?: return controller
        }
    }

    private fun NSData.toByteArray(): ByteArray {
        val size = length.toInt()
        val bytes = ByteArray(size)
        if (size > 0) bytes.usePinned { memcpy(it.addressOf(0), this.bytes, length) }
        return bytes
    }
}
