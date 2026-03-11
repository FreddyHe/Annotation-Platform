package com.annotation.platform.config;

import com.annotation.platform.service.upload.FileUploadService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;

@Slf4j
@Component
@EnableScheduling
@RequiredArgsConstructor
public class ScheduledTasks {

    private final FileUploadService fileUploadService;

    @Scheduled(cron = "0 0 2 * * ?")
    public void cleanupExpiredChunks() {
        log.info("开始执行定时任务：清理过期分块文件 - {}", LocalDateTime.now());
        try {
            fileUploadService.cleanupExpiredChunks();
            log.info("定时任务执行完成：清理过期分块文件 - {}", LocalDateTime.now());
        } catch (Exception e) {
            log.error("定时任务执行失败：清理过期分块文件 - {}", e.getMessage(), e);
        }
    }

    @Scheduled(cron = "0 0 3 * * ?")
    public void cleanupExpiredSessions() {
        log.info("开始执行定时任务：清理过期会话 - {}", LocalDateTime.now());
        try {
            LocalDateTime cutoffTime = LocalDateTime.now().minusDays(30);
            sessionRepository.deleteByExpiresAtBefore(cutoffTime);
            log.info("定时任务执行完成：清理过期会话 - {}", LocalDateTime.now());
        } catch (Exception e) {
            log.error("定时任务执行失败：清理过期会话 - {}", e.getMessage(), e);
        }
    }

    @Scheduled(cron = "0 0 4 * * ?")
    public void healthCheck() {
        log.info("系统健康检查 - {}", LocalDateTime.now());
    }

    private final com.annotation.platform.repository.SessionRepository sessionRepository;
}
