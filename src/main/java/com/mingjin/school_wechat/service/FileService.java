package com.mingjin.school_wechat.service;

import com.mingjin.school_wechat.common.auth.AuthContext;
import com.mingjin.school_wechat.common.exception.BusinessException;
import com.mingjin.school_wechat.mapper.FileMapper;
import com.mingjin.school_wechat.model.entity.FileResource;
import com.mingjin.school_wechat.model.request.CreateFileRequest;
import com.mingjin.school_wechat.model.view.FileAccessView;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;
import org.springframework.web.multipart.MultipartFile;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.time.LocalDate;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

@Service
public class FileService {

    private final FileMapper fileMapper;
    private final SyncEventService syncEventService;

    @Value("${app.storage.local-upload-dir:uploads}")
    private String localUploadDir;

    public FileService(FileMapper fileMapper, SyncEventService syncEventService) {
        this.fileMapper = fileMapper;
        this.syncEventService = syncEventService;
    }

    @Transactional
    public FileResource createMockFile(CreateFileRequest request) {
        if (request == null || !StringUtils.hasText(request.getFileName()) || !StringUtils.hasText(request.getFileUrl())) {
            throw new BusinessException("文件名和文件地址不能为空");
        }
        FileResource fileResource = new FileResource();
        fileResource.setUploaderUserId(AuthContext.getUserId());
        fileResource.setStorageType("external");
        fileResource.setBucketName("school-wechat-external");
        fileResource.setFileKey("external/" + UUID.randomUUID() + "_" + request.getFileName());
        fileResource.setFileName(request.getFileName());
        fileResource.setFileExt(request.getFileExt());
        fileResource.setMimeType(request.getMimeType());
        fileResource.setFileSize(request.getFileSize() == null ? 0L : request.getFileSize());
        fileResource.setChecksum(null);
        fileResource.setFileUrl(request.getFileUrl());
        fileResource.setThumbnailUrl(request.getThumbnailUrl());
        fileMapper.insertFileResource(fileResource);

        syncEventService.recordEvent(
                AuthContext.getUserId(),
                AuthContext.getDeviceId(),
                "file",
                "create",
                "file_resource",
                fileResource.getId(),
                Map.of("fileName", fileResource.getFileName())
        );
        return fileResource;
    }

    public FileResource getFile(Long fileId) {
        FileResource fileResource = fileMapper.findById(fileId);
        if (fileResource == null) {
            throw new BusinessException("文件不存在");
        }
        return fileResource;
    }

    public FileAccessView getFileAccessInfo(Long fileId, String baseUrl) {
        FileResource fileResource = getFile(fileId);
        return buildFileAccessView(fileResource, baseUrl);
    }

    public FileAccessView buildFileAccessView(FileResource fileResource, String baseUrl) {
        if (fileResource == null) {
            return null;
        }
        FileAccessView accessView = new FileAccessView();
        accessView.setFileId(fileResource.getId());
        accessView.setFileName(fileResource.getFileName());
        accessView.setMimeType(fileResource.getMimeType());
        accessView.setFileSize(fileResource.getFileSize());
        accessView.setFileSizeText(formatFileSize(fileResource.getFileSize()));
        accessView.setFileUrl(fileResource.getFileUrl());
        accessView.setThumbnailUrl(fileResource.getThumbnailUrl());
        accessView.setPreviewable(isPreviewable(fileResource.getMimeType()));
        accessView.setMediaCardType(detectMediaCardType(fileResource.getMimeType()));
        accessView.setDurationText(formatDuration(fileResource.getDurationSeconds()));
        accessView.setDisplayThumbnailUrl(resolveDisplayThumbnailUrl(fileResource));
        accessView.setFallbackIcon(resolveFallbackIcon(fileResource.getMimeType()));
        if (isLocalStorage(fileResource)) {
            Path localPath = resolveLocalPath(fileResource);
            boolean localFileExists = Files.exists(localPath) && Files.isRegularFile(localPath);
            accessView.setLocalFileExists(localFileExists);
            accessView.setPreviewUrl(buildPublicUrl(baseUrl, "/api/files/" + fileResource.getId() + "/preview"));
            accessView.setDownloadUrl(buildPublicUrl(baseUrl, "/api/files/" + fileResource.getId() + "/download"));
            if (localFileExists) {
                accessView.setFileStatus("available");
                accessView.setFileStatusMessage("文件可访问");
            } else {
                accessView.setFileStatus("missing");
                accessView.setFileStatusMessage("本地文件不存在或已被移除");
                accessView.setPreviewable(false);
                accessView.setDisplayThumbnailUrl(null);
            }
        } else {
            accessView.setLocalFileExists(false);
            accessView.setPreviewUrl(fileResource.getFileUrl());
            accessView.setDownloadUrl(fileResource.getFileUrl());
            accessView.setFileStatus("external");
            accessView.setFileStatusMessage("外部文件地址");
        }
        return accessView;
    }

    public Path getLocalFilePath(Long fileId) {
        FileResource fileResource = getFile(fileId);
        if (!isLocalStorage(fileResource)) {
            throw new BusinessException("当前文件不支持本地访问");
        }
        Path localPath = resolveLocalPath(fileResource);
        if (!Files.exists(localPath) || !Files.isRegularFile(localPath)) {
            throw new BusinessException("本地文件不存在");
        }
        return localPath;
    }

    @Transactional
    public FileResource uploadLocalFile(MultipartFile file,
                                        MultipartFile thumbnailFile,
                                        Integer durationSeconds,
                                        String baseUrl) {
        if (file == null || file.isEmpty()) {
            throw new BusinessException("上传文件不能为空");
        }
        String originalFileName = StringUtils.hasText(file.getOriginalFilename()) ? file.getOriginalFilename() : "file";
        String safeFileName = sanitizeFileName(originalFileName);
        String fileExt = extractExtension(safeFileName);
        String mimeType = resolveMimeType(file.getContentType(), fileExt);
        String mediaCategory = detectMediaCategory(mimeType);
        validateUploadInput(mediaCategory, thumbnailFile, durationSeconds);
        Path uploadRoot = Paths.get(localUploadDir).toAbsolutePath().normalize();
        LocalDate today = LocalDate.now();
        Path targetDir = uploadRoot.resolve(Paths.get(mediaCategory, String.valueOf(today.getYear()), String.format("%02d", today.getMonthValue())));
        createDirectories(targetDir);

        String storedFileName = UUID.randomUUID() + (StringUtils.hasText(fileExt) ? "." + fileExt : "");
        Path targetFile = targetDir.resolve(storedFileName);
        copyMultipartFile(file, targetFile);

        FileResource fileResource = new FileResource();
        fileResource.setUploaderUserId(AuthContext.getUserId());
        fileResource.setStorageType("local");
        fileResource.setBucketName("school-wechat-local");
        fileResource.setFileKey(uploadRoot.relativize(targetFile).toString().replace('\\', '/'));
        fileResource.setFileName(safeFileName);
        fileResource.setFileExt(fileExt);
        fileResource.setMimeType(mimeType);
        fileResource.setFileSize(file.getSize());
        fileResource.setChecksum(null);
        fileResource.setFileUrl(buildPublicUrl(baseUrl, "/uploads/" + fileResource.getFileKey()));
        fileResource.setDurationSeconds(durationSeconds);

        if ("image".equals(mediaCategory)) {
            applyImageMetadata(file, fileResource);
            fileResource.setThumbnailUrl(fileResource.getFileUrl());
        }

        if (thumbnailFile != null && !thumbnailFile.isEmpty()) {
            String thumbnailExt = extractExtension(sanitizeFileName(thumbnailFile.getOriginalFilename()));
            Path thumbnailDir = uploadRoot.resolve(Paths.get("thumbnail", String.valueOf(today.getYear()), String.format("%02d", today.getMonthValue())));
            createDirectories(thumbnailDir);
            Path thumbnailPath = thumbnailDir.resolve(UUID.randomUUID() + (StringUtils.hasText(thumbnailExt) ? "." + thumbnailExt : ""));
            copyMultipartFile(thumbnailFile, thumbnailPath);
            String thumbnailKey = uploadRoot.relativize(thumbnailPath).toString().replace('\\', '/');
            fileResource.setThumbnailUrl(buildPublicUrl(baseUrl, "/uploads/" + thumbnailKey));
        }

        fileMapper.insertFileResource(fileResource);
        syncEventService.recordEvent(
                AuthContext.getUserId(),
                AuthContext.getDeviceId(),
                "file",
                "create",
                "file_resource",
                fileResource.getId(),
                Map.of("fileName", fileResource.getFileName(), "mimeType", fileResource.getMimeType())
        );
        return fileResource;
    }

    private void validateUploadInput(String mediaCategory, MultipartFile thumbnailFile, Integer durationSeconds) {
        if (thumbnailFile != null && !thumbnailFile.isEmpty()) {
            if (!"video".equals(mediaCategory)) {
                throw new BusinessException("只有视频文件支持上传封面图");
            }
            String thumbnailMimeType = resolveMimeType(
                    thumbnailFile.getContentType(),
                    extractExtension(sanitizeFileName(thumbnailFile.getOriginalFilename()))
            );
            if (thumbnailMimeType == null || !thumbnailMimeType.startsWith("image/")) {
                throw new BusinessException("视频封面必须是图片文件");
            }
        }
        if (durationSeconds != null && durationSeconds <= 0) {
            throw new BusinessException("时长必须大于 0");
        }
        if ("voice".equals(mediaCategory) || "video".equals(mediaCategory)) {
            return;
        }
        if (durationSeconds != null) {
            throw new BusinessException("只有语音或视频文件支持时长参数");
        }
    }

    private void createDirectories(Path targetDir) {
        try {
            Files.createDirectories(targetDir);
        } catch (IOException e) {
            throw new BusinessException("创建本地上传目录失败");
        }
    }

    private void copyMultipartFile(MultipartFile file, Path targetFile) {
        try (InputStream inputStream = file.getInputStream()) {
            Files.copy(inputStream, targetFile, StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException e) {
            throw new BusinessException("保存本地文件失败");
        }
    }

    private void applyImageMetadata(MultipartFile file, FileResource fileResource) {
        try (InputStream inputStream = file.getInputStream()) {
            BufferedImage image = ImageIO.read(inputStream);
            if (image != null) {
                fileResource.setWidth(image.getWidth());
                fileResource.setHeight(image.getHeight());
            }
        } catch (IOException e) {
            throw new BusinessException("读取图片尺寸失败");
        }
    }

    private String sanitizeFileName(String fileName) {
        return fileName.replace("\\", "_").replace("/", "_").trim();
    }

    private String extractExtension(String fileName) {
        int dotIndex = fileName.lastIndexOf('.');
        if (dotIndex < 0 || dotIndex == fileName.length() - 1) {
            return "";
        }
        return fileName.substring(dotIndex + 1).toLowerCase(Locale.ROOT);
    }

    private String detectMediaCategory(String mimeType) {
        if (mimeType != null && mimeType.startsWith("image/")) {
            return "image";
        }
        if (mimeType != null && mimeType.startsWith("audio/")) {
            return "voice";
        }
        if (mimeType != null && mimeType.startsWith("video/")) {
            return "video";
        }
        return "file";
    }

    private String resolveMimeType(String mimeType, String fileExt) {
        if (StringUtils.hasText(mimeType) && !isGenericBinaryMimeType(mimeType)) {
            return mimeType;
        }
        return detectMimeType(fileExt);
    }

    private boolean isGenericBinaryMimeType(String mimeType) {
        return "application/octet-stream".equalsIgnoreCase(mimeType)
                || "binary/octet-stream".equalsIgnoreCase(mimeType);
    }

    private String detectMimeType(String fileExt) {
        return switch (fileExt) {
            case "png" -> "image/png";
            case "jpg", "jpeg" -> "image/jpeg";
            case "gif" -> "image/gif";
            case "webp" -> "image/webp";
            case "bmp" -> "image/bmp";
            case "svg" -> "image/svg+xml";
            case "mp3" -> "audio/mpeg";
            case "wav" -> "audio/wav";
            case "m4a" -> "audio/mp4";
            case "aac" -> "audio/aac";
            case "ogg" -> "audio/ogg";
            case "flac" -> "audio/flac";
            case "mp4" -> "video/mp4";
            case "mov" -> "video/quicktime";
            case "m4v" -> "video/x-m4v";
            case "webm" -> "video/webm";
            case "avi" -> "video/x-msvideo";
            case "mkv" -> "video/x-matroska";
            case "pdf" -> "application/pdf";
            case "doc" -> "application/msword";
            case "docx" -> "application/vnd.openxmlformats-officedocument.wordprocessingml.document";
            case "xls" -> "application/vnd.ms-excel";
            case "xlsx" -> "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet";
            case "ppt" -> "application/vnd.ms-powerpoint";
            case "pptx" -> "application/vnd.openxmlformats-officedocument.presentationml.presentation";
            case "txt" -> "text/plain";
            case "csv" -> "text/csv";
            case "zip" -> "application/zip";
            case "rar" -> "application/vnd.rar";
            case "7z" -> "application/x-7z-compressed";
            default -> "application/octet-stream";
        };
    }

    private String buildPublicUrl(String baseUrl, String relativePath) {
        if (!StringUtils.hasText(baseUrl)) {
            return relativePath;
        }
        return StringUtils.trimTrailingCharacter(baseUrl, '/') + relativePath;
    }

    private boolean isLocalStorage(FileResource fileResource) {
        return "local".equalsIgnoreCase(fileResource.getStorageType()) && StringUtils.hasText(fileResource.getFileKey());
    }

    private Path resolveLocalPath(FileResource fileResource) {
        Path uploadRoot = Paths.get(localUploadDir).toAbsolutePath().normalize();
        Path resolvedPath = uploadRoot.resolve(fileResource.getFileKey()).normalize();
        if (!resolvedPath.startsWith(uploadRoot)) {
            throw new BusinessException("文件路径非法");
        }
        return resolvedPath;
    }

    private boolean isPreviewable(String mimeType) {
        if (!StringUtils.hasText(mimeType)) {
            return false;
        }
        return mimeType.startsWith("image/")
                || mimeType.startsWith("audio/")
                || mimeType.startsWith("video/")
                || "application/pdf".equalsIgnoreCase(mimeType)
                || mimeType.startsWith("text/");
    }

    private String formatFileSize(Long fileSize) {
        if (fileSize == null || fileSize <= 0) {
            return "0 B";
        }
        double size = fileSize.doubleValue();
        String[] units = {"B", "KB", "MB", "GB"};
        int unitIndex = 0;
        while (size >= 1024 && unitIndex < units.length - 1) {
            size = size / 1024;
            unitIndex++;
        }
        if (unitIndex == 0) {
            return String.format(Locale.ROOT, "%.0f %s", size, units[unitIndex]);
        }
        return String.format(Locale.ROOT, "%.1f %s", size, units[unitIndex]);
    }

    private String detectMediaCardType(String mimeType) {
        if (!StringUtils.hasText(mimeType)) {
            return "file";
        }
        if (mimeType.startsWith("image/")) {
            return "image";
        }
        if (mimeType.startsWith("audio/")) {
            return "voice";
        }
        if (mimeType.startsWith("video/")) {
            return "video";
        }
        return "file";
    }

    private String resolveDisplayThumbnailUrl(FileResource fileResource) {
        String mediaCardType = detectMediaCardType(fileResource.getMimeType());
        return switch (mediaCardType) {
            case "image" -> StringUtils.hasText(fileResource.getThumbnailUrl()) ? fileResource.getThumbnailUrl() : fileResource.getFileUrl();
            case "video" -> fileResource.getThumbnailUrl();
            default -> null;
        };
    }

    private String resolveFallbackIcon(String mimeType) {
        return detectMediaCardType(mimeType);
    }

    private String formatDuration(Integer durationSeconds) {
        if (durationSeconds == null || durationSeconds <= 0) {
            return null;
        }
        int totalSeconds = durationSeconds;
        int hours = totalSeconds / 3600;
        int minutes = (totalSeconds % 3600) / 60;
        int seconds = totalSeconds % 60;
        if (hours > 0) {
            return String.format(Locale.ROOT, "%02d:%02d:%02d", hours, minutes, seconds);
        }
        return String.format(Locale.ROOT, "%02d:%02d", minutes, seconds);
    }
}
