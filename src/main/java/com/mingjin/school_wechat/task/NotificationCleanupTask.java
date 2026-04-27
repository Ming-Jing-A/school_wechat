package com.mingjin.school_wechat.task;

import com.mingjin.school_wechat.service.NotificationService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
public class NotificationCleanupTask {

    private static final Logger log = LoggerFactory.getLogger(NotificationCleanupTask.class);

    private final NotificationService notificationService;

    public NotificationCleanupTask(NotificationService notificationService) {
        this.notificationService = notificationService;
    }

    @Scheduled(cron = "0 0 2 * * ?")
    public void cleanupOldNotifications() {
        log.info("开始清理7天前的通知...");
        try {
            notificationService.cleanOldNotifications(7);
            log.info("通知清理完成");
        } catch (Exception e) {
            log.error("通知清理失败", e);
        }
    }
}
