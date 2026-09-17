package com.example.server.controller;

import com.example.server.common.Result;
import com.example.server.dto.MediaSummary;
import com.example.server.entity.MediaFile;
import com.example.server.service.AuthService;
import com.example.server.service.ChunkUploadService;
import com.example.server.service.MediaIngestService;
import com.example.server.service.MediaService;
import com.example.server.utils.MinioUtils;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.HttpHeaders;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestAttribute;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

@RestController
@RequestMapping("/media")
public class MediaController {

    private static final Map<String, String> VIDEO_CONTENT_TYPES = Map.of(
            ".mp4", "video/mp4",
            ".m4v", "video/mp4",
            ".webm", "video/webm",
            ".mov", "video/quicktime",
            ".mkv", "video/x-matroska",
            ".avi", "video/x-msvideo",
            ".mpg", "video/mpeg",
            ".mpeg", "video/mpeg",
            ".ogv", "video/ogg");

    private final ChunkUploadService chunkUploadService;
    private final MediaIngestService mediaIngestService;
    private final MediaService mediaService;
    private final MinioUtils minioUtils;
    private final AuthService authService;

    public MediaController(ChunkUploadService chunkUploadService,
                           MediaIngestService mediaIngestService,
                           MediaService mediaService,
                           MinioUtils minioUtils,
                           AuthService authService) {
        this.chunkUploadService = chunkUploadService;
        this.mediaIngestService = mediaIngestService;
        this.mediaService = mediaService;
        this.minioUtils = minioUtils;
        this.authService = authService;
    }

    // 说明：下列方法上的 throws 源于 service 层声明了受检异常（throws Exception/IOException）。
    // 受检异常必须在编译期被处理，而 @RestControllerAdvice 只在运行期兜底，故此处显式向上抛出，
    // 由全局异常处理器统一转成 Result。更彻底的做法是收敛 service 的受检异常签名（留待 service 批次）。
    @PostMapping("/init-upload")
    public Result<String> initUpload(@RequestParam String filename,
                                     @RequestParam int totalChunks,
                                     @RequestAttribute(AuthService.REQUEST_USER_ID) Long userId) throws Exception {
        return Result.ok(chunkUploadService.initialize(filename, totalChunks, userId));
    }

    @GetMapping("/upload-status")
    public Result<Set<Integer>> uploadStatus(
            @RequestParam String uploadId,
            @RequestAttribute(AuthService.REQUEST_USER_ID) Long userId) {
        return Result.ok(chunkUploadService.uploadedChunks(uploadId, userId));
    }

    @PostMapping("/upload-chunk")
    public Result<Void> uploadChunk(@RequestParam String uploadId,
                                    @RequestParam int chunkIndex,
                                    @RequestParam int totalChunks,
                                    @RequestParam("file") MultipartFile file,
                                    @RequestAttribute(AuthService.REQUEST_USER_ID) Long userId) throws Exception {
        chunkUploadService.uploadChunk(uploadId, chunkIndex, totalChunks, file, userId);
        return Result.ok();
    }

    @PostMapping("/complete-upload")
    public Result<MediaSummary> completeUpload(
            @RequestParam String uploadId,
            @RequestAttribute(AuthService.REQUEST_USER_ID) Long userId) throws Exception {
        return Result.ok(MediaSummary.from(chunkUploadService.complete(uploadId, userId)));
    }

    @PostMapping("/upload")
    public Result<MediaSummary> upload(@RequestParam("file") MultipartFile file,
                                       @RequestAttribute(AuthService.REQUEST_USER_ID) Long userId) throws Exception {
        return Result.ok(MediaSummary.from(mediaIngestService.ingestFile(file, userId)));
    }

    @PostMapping("/upload-url")
    public Result<MediaSummary> uploadUrl(@RequestParam("url") String url,
                                          @RequestAttribute(AuthService.REQUEST_USER_ID) Long userId) throws Exception {
        return Result.ok(MediaSummary.from(mediaIngestService.ingestUrl(url, userId)));
    }

    @GetMapping("/list")
    public Result<List<MediaSummary>> getList(
            @RequestAttribute(AuthService.REQUEST_USER_ID) Long userId) {
        return Result.ok(mediaService.listByUser(userId).stream()
                .map(MediaSummary::from)
                .toList());
    }

    @GetMapping("/playback")
    public Result<String> playback(@RequestParam Long id,
                                   @RequestAttribute(AuthService.REQUEST_USER_ID) Long userId) {
        MediaFile mediaFile = mediaService.requireOwnedMedia(id, userId);
        String source = mediaFile.getFilePath();
        // 存于 MinIO 的文件改走同源流式代理：预签名地址指向容器内网（如 localhost:9000），
        // 浏览器无法访问，播放必然失败。外链来源（非受管文件）维持原地址直放。
        if (minioUtils.isManagedFile(source)) {
            return Result.ok("/media/stream?id=" + id);
        }
        return Result.ok(source);
    }

    /**
     * 同源媒体流式代理：从 MinIO 按 Range 分片吐字节，支持 <video> 拖拽与元数据预读。
     * 该路径已从 AuthInterceptor 排除，鉴权在接口内完成——<video> 标签发起的请求无法携带
     * Authorization 头，前端会把登录 token 拼在查询参数里传进来。
     */
    @GetMapping("/stream")
    public void stream(@RequestParam Long id,
                       @RequestParam(required = false) String token,
                       @RequestAttribute(value = AuthService.REQUEST_USER_ID, required = false) Long headerUserId,
                       @RequestHeader(value = HttpHeaders.RANGE, required = false) String rangeHeader,
                       HttpServletResponse response) throws IOException {
        Long userId = headerUserId;
        if (userId == null) {
            if (token == null || token.isBlank()) throw new SecurityException("请先登录");
            userId = authService.resolveUser("Bearer " + token);
        }
        MediaFile mediaFile = mediaService.requireOwnedMedia(id, userId);
        String source = mediaFile.getFilePath();

        if (!minioUtils.isManagedFile(source)) {
            response.sendRedirect(source);
            return;
        }

        long total = minioUtils.fileSize(source);
        String contentType = minioUtils.fileContentType(source);
        if (contentType == null || contentType.isBlank() || "application/octet-stream".equals(contentType)) {
            contentType = guessVideoContentType(mediaFile.getFilename());
        }
        response.setHeader(HttpHeaders.ACCEPT_RANGES, "bytes");

        long start = 0;
        long end = total - 1;
        boolean partial = false;
        if (rangeHeader != null && rangeHeader.startsWith("bytes=")) {
            String spec = rangeHeader.substring("bytes=".length()).split(",", 2)[0].trim();
            int dash = spec.indexOf('-');
            try {
                if (dash > 0) {
                    start = Long.parseLong(spec.substring(0, dash));
                    end = dash == spec.length() - 1 ? total - 1 : Long.parseLong(spec.substring(dash + 1));
                } else if (dash == 0) {
                    long suffixLength = Long.parseLong(spec.substring(1));
                    start = Math.max(0, total - suffixLength);
                    end = total - 1;
                }
            } catch (NumberFormatException ignored) {
                start = 0;
                end = total - 1;
            }
            if (start >= total) {
                response.setStatus(HttpServletResponse.SC_REQUESTED_RANGE_NOT_SATISFIABLE);
                response.setHeader(HttpHeaders.CONTENT_RANGE, "bytes */" + total);
                return;
            }
            if (start >= 0) {
                end = Math.min(end, total - 1);
                if (end < start) {
                    start = 0;
                    end = total - 1;
                } else {
                    partial = true;
                }
            }
        }

        long length = end - start + 1;
        if (partial) {
            response.setStatus(HttpServletResponse.SC_PARTIAL_CONTENT);
            response.setHeader(HttpHeaders.CONTENT_RANGE, "bytes " + start + "-" + end + "/" + total);
        } else {
            response.setStatus(HttpServletResponse.SC_OK);
        }
        response.setContentType(contentType);
        response.setContentLengthLong(length);
        minioUtils.copyFileRange(source, start, length, response.getOutputStream());
    }

    private String guessVideoContentType(String filename) {
        if (filename == null) return "application/octet-stream";
        int dot = filename.lastIndexOf('.');
        if (dot < 0) return "application/octet-stream";
        String suffix = filename.substring(dot).toLowerCase(Locale.ROOT);
        return VIDEO_CONTENT_TYPES.getOrDefault(suffix, "application/octet-stream");
    }

    @DeleteMapping("/delete")
    public Result<Void> delete(@RequestParam("id") Long id,
                               @RequestAttribute(AuthService.REQUEST_USER_ID) Long userId) {
        mediaService.deleteOwnedMedia(id, userId);
        return Result.ok();
    }
}
