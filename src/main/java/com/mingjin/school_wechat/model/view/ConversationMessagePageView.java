package com.mingjin.school_wechat.model.view;

import lombok.Data;

import java.util.List;

@Data
public class ConversationMessagePageView {
    private List<ConversationMessageView> list;
    private Integer limit;
    private Long nextBeforeMessageId;
    private Boolean hasMore;
}
