package com.mingjin.school_wechat.model.request;

import lombok.Data;

@Data
public class CreateFileRequest {
    private String fileName;
    private String fileExt;
    private String mimeType;
    private Long fileSize;
    private String fileUrl;
    private String thumbnailUrl;
}
