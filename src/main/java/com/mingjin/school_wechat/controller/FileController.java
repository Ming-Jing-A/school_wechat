package com.mingjin.school_wechat.controller;

import com.mingjin.school_wechat.common.api.ApiResponse;
import com.mingjin.school_wechat.common.auth.AuthContext;
import com.mingjin.school_wechat.model.entity.ChatMessage;
import com.mingjin.school_wechat.model.entity.Conversation;
import com.mingjin.school_wechat.model.entity.FileResource;
import com.mingjin.school_wechat.model.entity.FriendRequest;
import com.mingjin.school_wechat.model.entity.WechatUser;
import com.mingjin.school_wechat.model.request.CreateFileRequest;
import com.mingjin.school_wechat.model.request.CreateGroupRequest;
import com.mingjin.school_wechat.model.request.HandleFriendRequestRequest;
import com.mingjin.school_wechat.model.request.LoginRequest;
import com.mingjin.school_wechat.model.request.SendFriendRequestRequest;
import com.mingjin.school_wechat.model.request.SendMessageRequest;
import com.mingjin.school_wechat.model.view.ConversationMessageView;
import com.mingjin.school_wechat.model.view.ConversationSummaryView;
import com.mingjin.school_wechat.model.view.FileAccessView;
import com.mingjin.school_wechat.model.view.FriendRequestView;
import com.mingjin.school_wechat.model.view.FriendView;
import com.mingjin.school_wechat.model.view.LoginResponse;
import com.mingjin.school_wechat.model.view.UserNotificationView;
import com.mingjin.school_wechat.model.view.UserSyncEventView;
import com.mingjin.school_wechat.service.AuthService;
import com.mingjin.school_wechat.service.ConversationService;
import com.mingjin.school_wechat.service.FileService;
import com.mingjin.school_wechat.service.FriendService;
import com.mingjin.school_wechat.service.NotificationService;
import com.mingjin.school_wechat.service.SyncEventService;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.core.io.FileSystemResource;
import org.springframework.core.io.Resource;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.io.IOException;
import java.util.List;

@RestController
@RequestMapping("/api")
public class FileController {

    private final FileService fileService;

    public FileController(FileService fileService) {
        this.fileService = fileService;
    }

    @PostMapping("/files/mock-upload")
    public ApiResponse<FileResource> createMockFile(@RequestBody CreateFileRequest request) {
        return ApiResponse.success("文件元数据创建成功", fileService.createMockFile(request));
    }

    @PostMapping("/files/upload")
    public ApiResponse<FileResource> uploadLocalFile(@RequestPart("file") MultipartFile file,
                                                     @RequestPart(value = "thumbnailFile", required = false) MultipartFile thumbnailFile,
                                                     @RequestParam(value = "durationSeconds", required = false) Integer durationSeconds,
                                                     HttpServletRequest request) {
        return ApiResponse.success(
                "文件上传成功",
                fileService.uploadLocalFile(file, thumbnailFile, durationSeconds, getBaseUrl(request))
        );
    }

    @GetMapping("/files/{fileId}")
    public ApiResponse<FileResource> getFile(@PathVariable Long fileId) {
        return ApiResponse.success(fileService.getFile(fileId));
    }

    @GetMapping("/files/{fileId}/access")
    public ApiResponse<FileAccessView> getFileAccess(@PathVariable Long fileId, HttpServletRequest request) {
        return ApiResponse.success(fileService.getFileAccessInfo(fileId, getBaseUrl(request)));
    }

    @GetMapping("/files/{fileId}/preview")
    public ResponseEntity<Resource> previewFile(@PathVariable Long fileId) {
        return buildFileResponse(fileId, false);
    }

    @GetMapping("/files/{fileId}/download")
    public ResponseEntity<Resource> downloadFile(@PathVariable Long fileId) {
        return buildFileResponse(fileId, true);
    }

    private String getBaseUrl(HttpServletRequest request) {
        // 使用相对路径，让前端自动拼接完整URL
        return "";
    }

    private ResponseEntity<Resource> buildFileResponse(Long fileId, boolean attachment) {
        FileResource fileResource = fileService.getFile(fileId);
        Path filePath = fileService.getLocalFilePath(fileId);
        Resource resource = new FileSystemResource(filePath);
        MediaType mediaType = MediaType.APPLICATION_OCTET_STREAM;
        if (fileResource.getMimeType() != null) {
            mediaType = MediaType.parseMediaType(fileResource.getMimeType());
        }
        ContentDisposition contentDisposition = attachment
                ? ContentDisposition.attachment().filename(fileResource.getFileName(), StandardCharsets.UTF_8).build()
                : ContentDisposition.inline().filename(fileResource.getFileName(), StandardCharsets.UTF_8).build();
        long contentLength = fileResource.getFileSize() == null ? getFileSize(filePath) : fileResource.getFileSize();
        return ResponseEntity.ok()
                .contentType(mediaType)
                .contentLength(contentLength)
                .header(HttpHeaders.CONTENT_DISPOSITION, contentDisposition.toString())
                .body(resource);
    }

    private long getFileSize(Path filePath) {
        try {
            return Files.size(filePath);
        } catch (IOException e) {
            return 0L;
        }
    }
}
