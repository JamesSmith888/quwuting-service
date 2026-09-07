package org.quwuting.quwutingservice.wxsubscribe.repository;

import org.quwuting.quwutingservice.wxsubscribe.entity.WxSubscribeLog;
import org.springframework.data.jpa.repository.JpaRepository;

/** 微信订阅消息发送留痕仓储（只写，2026-09-07 V11） */
public interface WxSubscribeLogRepository extends JpaRepository<WxSubscribeLog, Long> {
}
