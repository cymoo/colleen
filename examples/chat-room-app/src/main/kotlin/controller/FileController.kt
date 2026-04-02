package controller

import io.github.cymoo.colleen.*
import service.FileService
import java.nio.file.Files

/**
 * File download controller
 */
@Controller("/uploads")
class FileController(private val fileService: FileService) {
    
    @Get("/{filename}")
    fun downloadFile(filename: Path<String>, ctx: Context) {
        val file = fileService.getFile(filename.value) ?: throw NotFound("File not found")
        
        val mimeType = Files.probeContentType(file.toPath()) ?: "application/octet-stream"
        ctx.stream(file.inputStream(), mimeType)
    }
}
