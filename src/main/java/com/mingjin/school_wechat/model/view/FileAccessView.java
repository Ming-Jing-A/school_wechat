package com.mingjin.school_wechat.model.view;

import lombok.Data;

@Data
public class FileAccessView {
    private Long fileId;
    private String fileName;
    private String mimeType;
    private Long fileSize;
    private String fileSizeText;
    private String fileUrl;
    private String thumbnailUrl;
    private String previewUrl;
    private String downloadUrl;
    private Boolean previewable;
    private Boolean localFileExists;
    private String fileStatus;
    private String fileStatusMessage;
    private String mediaCardType;
    private String durationText;
    private String displayThumbnailUrl;
    private String fallbackIcon;
}
