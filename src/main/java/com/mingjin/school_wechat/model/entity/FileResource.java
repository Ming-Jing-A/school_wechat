package com.mingjin.school_wechat.model.entity;

import lombok.Data;

@Data
public class FileResource {
    private Long id;
    private Long uploaderUserId;
    private String storageType;
    private String bucketName;
    private String fileKey;
    private String fileName;
    private String fileExt;
    private String mimeType;
    private Long fileSize;
    private String checksum;
    private String fileUrl;
    private String thumbnailUrl;
    private Integer width;
    private Integer height;
    private Integer durationSeconds;
}
