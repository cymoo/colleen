import io.github.cymoo.colleen.BadRequest
import io.github.cymoo.colleen.Colleen
import io.github.cymoo.colleen.FileItem
import io.github.cymoo.colleen.NotFound
import io.github.cymoo.colleen.middleware.RequestLogger
import java.io.File
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.text.SimpleDateFormat
import java.util.*

/**
 * File Upload and Download Application
 *
 * Features demonstrated:
 * - Single file upload
 * - Multiple file upload
 * - File size validation
 * - File type validation
 * - File download
 * - File listing
 * - Basic static file management
 */
class FileManager(uploadDir: String) {

    private val uploadPath: Path = Paths.get(uploadDir)

    init {
        // Ensure upload directory exists
        Files.createDirectories(uploadPath)
    }

    /**
     * Metadata information for stored files
     */
    data class FileInfo(
        val name: String,
        val size: Long,
        val uploadTime: String,
        val mimeType: String?
    )

    /**
     * Save uploaded file to local storage with a unique filename
     */
    fun saveFile(file: FileItem): FileInfo {
        // Generate safe filename to prevent path traversal attacks
        val safeFilename = sanitizeFilename(file.filename)
        val timestamp = System.currentTimeMillis()
        val uniqueFilename = "${timestamp}_$safeFilename"

        val targetPath = uploadPath.resolve(uniqueFilename)
        file.save(targetPath.toString())

        return FileInfo(
            name = uniqueFilename,
            size = file.size,
            uploadTime = formatTime(timestamp),
            mimeType = file.contentType
        )
    }

    /**
     * Retrieve a file by name
     */
    fun getFile(filename: String): File? {
        val safeFilename = sanitizeFilename(filename)
        val file = uploadPath.resolve(safeFilename).toFile()
        return if (file.exists() && file.isFile) file else null
    }

    /**
     * List all uploaded files with metadata
     */
    fun listFiles(): List<FileInfo> {
        return Files.list(uploadPath)
            .filter { Files.isRegularFile(it) }
            .map { path ->
                val file = path.toFile()
                FileInfo(
                    name = file.name,
                    size = file.length(),
                    uploadTime = formatTime(file.lastModified()),
                    mimeType = Files.probeContentType(path)
                )
            }
            .sorted { a, b -> b.uploadTime.compareTo(a.uploadTime) }
            .toList()
    }

    /**
     * Delete a file from storage
     */
    fun deleteFile(filename: String): Boolean {
        val safeFilename = sanitizeFilename(filename)
        val file = uploadPath.resolve(safeFilename).toFile()
        return if (file.exists() && file.isFile) file.delete() else false
    }

    /**
     * Sanitize filename to prevent security issues
     */
    private fun sanitizeFilename(filename: String): String {
        return filename
            .replace('/', '_')
            .replace('\\', '_')
            .replace(Regex("[^a-zA-Z0-9._-]"), "_")
    }

    /**
     * Format timestamp to readable string
     */
    private fun formatTime(timestamp: Long): String {
        val sdf = SimpleDateFormat("yyyy-MM-dd HH:mm:ss")
        return sdf.format(Date(timestamp))
    }
}

/**
 * Main application entry point
 */
fun main() {
    val app = Colleen()

    // Configure maximum request body size (30MB)
    app.config.server {
        maxRequestSize = 30 * 1024 * 1024
    }

    // Register FileManager as application-level service
    app.provide(FileManager("uploads"))

    // Enable request logging middleware
    app.use(RequestLogger())

    // File management REST API
    app.group("/api/files") {

        // Upload a single file
        post("/upload") { ctx ->
            val fileManager = ctx.getService<FileManager>()
            val file = ctx.file("file")
                ?: throw BadRequest("No file uploaded")

            // Validate file size (max 5MB)
            val maxSize = 5 * 1024 * 1024
            if (file.size > maxSize) {
                throw BadRequest("File too large. Maximum size: 5MB")
            }

            // Validate file type
            val allowedTypes = setOf(
                "image/jpeg", "image/png", "image/gif",
                "application/pdf", "text/plain"
            )

            if (file.contentType !in allowedTypes) {
                throw BadRequest("File type not allowed. Allowed: JPEG, PNG, GIF, PDF, TXT")
            }

            val fileInfo = fileManager.saveFile(file)

            mapOf(
                "message" to "File uploaded successfully",
                "file" to fileInfo
            )
        }

        // Upload multiple files
        post("/upload-multiple") { ctx ->
            val fileManager = ctx.getService<FileManager>()
            val files = ctx.request.files("files")

            if (files.isEmpty()) {
                throw BadRequest("No files uploaded")
            }

            val results = files.map { file ->
                try {
                    if (file.size > 5 * 1024 * 1024) {
                        mapOf(
                            "filename" to file.filename,
                            "success" to false,
                            "error" to "File too large"
                        )
                    } else {
                        val fileInfo = fileManager.saveFile(file)
                        mapOf(
                            "filename" to file.filename,
                            "success" to true,
                            "file" to fileInfo
                        )
                    }
                } catch (e: Exception) {
                    mapOf(
                        "filename" to file.filename,
                        "success" to false,
                        "error" to e.message
                    )
                }
            }

            mapOf(
                "message" to "Upload completed",
                "results" to results
            )
        }

        // Get file list
        get("/") { ctx ->
            ctx.getService<FileManager>().listFiles()
        }

        // Download file
        get("/{filename}") { ctx ->
            val fileManager = ctx.getService<FileManager>()
            val filename = ctx.pathParam("filename")
                ?: throw BadRequest("Filename required")

            val file = fileManager.getFile(filename)
                ?: throw NotFound("File not found")

            // Set download header
            ctx.header("Content-Disposition", """attachment; filename="$filename"""")

            val mimeType = Files.probeContentType(file.toPath())
                ?: "application/octet-stream"

            ctx.stream(file.inputStream(), mimeType)
        }

        // Delete file
        delete("/{filename}") { ctx ->
            val fileManager = ctx.getService<FileManager>()
            val filename = ctx.pathParam("filename")
                ?: throw BadRequest("Filename required")

            val deleted = fileManager.deleteFile(filename)
            if (!deleted) {
                throw NotFound("File not found")
            }

            mapOf("message" to "File deleted successfully")
        }
    }

    // Simple web interface for testing file operations
    app.get("/") { ctx ->
        ctx.html(
            $$"""
            <!DOCTYPE html>
            <html>
            <head>
                <title>File Upload & Download</title>
                <meta charset="utf-8">
                <style>
                    body { 
                        font-family: Arial, sans-serif; 
                        max-width: 1000px; 
                        margin: 50px auto; 
                        padding: 20px; 
                    }
                    h1 { color: #333; }
                    .upload-section { 
                        background: #f9f9f9; 
                        padding: 20px; 
                        border-radius: 8px; 
                        margin: 20px 0; 
                    }
                    input[type="file"] { margin: 10px 0; }
                    button { 
                        background: #0066cc; 
                        color: white; 
                        border: none; 
                        padding: 10px 20px; 
                        border-radius: 4px; 
                        cursor: pointer; 
                    }
                    button:hover { background: #0052a3; }
                    .file-list { margin-top: 30px; }
                    table { 
                        width: 100%; 
                        border-collapse: collapse; 
                        margin-top: 10px; 
                    }
                    th, td { 
                        text-align: left; 
                        padding: 12px; 
                        border-bottom: 1px solid #ddd; 
                    }
                    th { background: #f5f5f5; }
                    .actions button { 
                        margin: 0 5px; 
                        padding: 5px 10px; 
                        font-size: 12px; 
                    }
                    .message { 
                        padding: 10px; 
                        margin: 10px 0; 
                        border-radius: 4px; 
                    }
                    .success { background: #d4edda; color: #155724; }
                    .error { background: #f8d7da; color: #721c24; }
                </style>
            </head>
            <body>
                <h1>📁 File Upload & Download System</h1>
                
                <div class="upload-section">
                    <h2>Upload Files</h2>
                    <p>Supported: JPEG, PNG, GIF, PDF, TXT (up to 5 MB)</p>
                    
                    <h3>Single File Upload</h3>
                    <input type="file" id="singleFile" />
                    <button onclick="uploadSingle()">Upload</button>
                    
                    <h3>Multiple File Upload</h3>
                    <input type="file" id="multipleFiles" multiple />
                    <button onclick="uploadMultiple()">Batch Upload</button>
                    
                    <div id="message"></div>
                </div>
                
                <div class="file-list">
                    <h2>File List</h2>
                    <button onclick="loadFiles()">Refresh List</button>
                    <table id="fileTable">
                        <thead>
                            <tr>
                                <th>File Name</th>
                                <th>Size</th>
                                <th>Upload Time</th>
                                <th>Type</th>
                                <th>Actions</th>
                            </tr>
                        </thead>
                        <tbody id="fileList"></tbody>
                    </table>
                </div>
                
                <script>
                    function showMessage(text, isError = false) {
                        const msg = document.getElementById('message');
                        msg.textContent = text;
                        msg.className = 'message ' + (isError ? 'error' : 'success');
                        setTimeout(() => {
                            msg.textContent = ''
                            msg.className = 'message'
                        }, 3000);
                    }
                    
                    async function uploadSingle() {
                        const input = document.getElementById('singleFile');
                        const file = input.files[0];
                        if (!file) {
                            showMessage('Please select a file', true);
                            return;
                        }
                        
                        const formData = new FormData();
                        formData.append('file', file);
                        
                        try {
                            const res = await fetch('/api/files/upload', {
                                method: 'POST',
                                body: formData
                            });
                            const data = await res.json();
                            
                            if (res.ok) {
                                showMessage('Upload successful!');
                                input.value = '';
                                loadFiles();
                            } else {
                                showMessage(data.error || 'Upload failed', true);
                            }
                        } catch (err) {
                            showMessage('Upload failed: ' + err.message, true);
                        }
                    }
                    
                    async function uploadMultiple() {
                        const input = document.getElementById('multipleFiles');
                        const files = input.files;
                        if (files.length === 0) {
                            showMessage('Please select files', true);
                            return;
                        }
                        
                        const formData = new FormData();
                        for (let file of files) {
                            formData.append('files', file);
                        }
                        
                        try {
                            const res = await fetch('/api/files/upload-multiple', {
                                method: 'POST',
                                body: formData
                            });
                            const data = await res.json();
                            
                            if (res.ok) {
                                const success = data.results.filter(r => r.success).length;
                                showMessage(`upload ${success}/${files.length} files successfully`);
                                input.value = '';
                                loadFiles();
                            } else {
                                showMessage(data.error || 'Upload failed', true);
                            }
                        } catch (err) {
                            showMessage('Upload failed: ' + err.message, true);
                        }
                    }
                    
                    async function loadFiles() {
                        try {
                            const res = await fetch('/api/files');
                            const files = await res.json();
                            
                            const tbody = document.getElementById('fileList');
                            tbody.innerHTML = files.map(file => `
                                <tr>
                                    <td>${file.name}</td>
                                    <td>${formatSize(file.size)}</td>
                                    <td>${file.uploadTime}</td>
                                    <td>${file.mimeType || 'unknown'}</td>
                                    <td class="actions">
                                        <button class="download-btn" title="download" onclick="downloadFile('${file.name}')">⬇️</button>
                                        <button class="delete-btn" title="delete" onclick="deleteFile('${file.name}')">❌️</button>
                                    </td>
                                </tr>
                            `).join('');
                        } catch (err) {
                            showMessage('Failed to load file list', true);
                        }
                    }
                    
                    function downloadFile(filename) {
                        window.location.href = `/api/files/${encodeURIComponent(filename)}`;
                    }
                    
                    async function deleteFile(filename) {
                        if (!confirm('Are you sure you want to delete this file?')) return;
                        
                        try {
                            const res = await fetch(`/api/files/${encodeURIComponent(filename)}`, {
                                method: 'DELETE'
                            });
                            
                            if (res.ok) {
                                showMessage('Deleted successfully');
                                loadFiles();
                            } else {
                                showMessage('Delete failed', true);
                            }
                        } catch (err) {
                            showMessage('Delete failed: ' + err.message, true);
                        }
                    }
                    
                    function formatSize(bytes) {
                        if (bytes < 1024) return bytes + ' B';
                        if (bytes < 1024 * 1024) return (bytes / 1024).toFixed(2) + ' KB';
                        return (bytes / (1024 * 1024)).toFixed(2) + ' MB';
                    }
                    
                    // Load file list on page load
                    loadFiles();
                </script>
            </body>
            </html>
        """.trimIndent()
        )
    }

    app.listen(8000)
    println("✅ File Upload/Download Server running on http://localhost:8000")
}
