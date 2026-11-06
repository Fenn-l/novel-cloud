package io.github.xxyopen.novel.book.manager.mq;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.xxyopen.novel.common.constant.AmqpConsts;
import io.github.xxyopen.novel.common.dto.BookChangeMsg;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.SneakyThrows;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.rabbit.connection.CorrelationData;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.nio.charset.StandardCharsets;
import java.util.UUID;

/**
 * 「小说信息变更」事件生产者（由 novel-book 的发布章节链路调用）。
 *
 * <p>可靠性设计（面试重点，逐层说明）：
 * <ol>
 *   <li><b>事务后发送</b>：注册 TransactionSynchronization.afterCommit，
 *       保证「事务回滚但消息已发出」这种脏消息不会出现；</li>
 *   <li><b>publisher confirm</b>：开启 correlated 确认后，broker 未确认（nack / 超时 / 宕机）
 *       会回调 ConfirmCallback。此时事务已提交、无法回滚，所以把消息体写入 Redis
 *       待重放列表 {@code book:mq:send:failed}，交由补偿任务重放；</li>
 *   <li><b>publisher returns</b>：mandatory=true 时消息无法路由到任何队列会触发
 *       ReturnCallback，避免"交换机收到了但没人消费"的静默丢失；</li>
 *   <li><b>兜底</b>：XXL-JOB 的 saveToEsJobHandler 每月全量重建 ES 索引，
 *       即使增量链路整体故障，最终一致性仍可恢复。</li>
 * </ol>
 *
 * <p>诚实边界：confirm 只能保证 broker 收到消息，不能保证消费成功；
 * 生产级方案应把「业务数据 + 待发消息」放在同一个本地事务里落库（本地消息表 / Outbox），
 * 再由独立投递任务扫描发送。本项目用「Redis 待重放列表 + 全量同步兜底」做了轻量近似，
 * 代价是极端情况下（写 Redis 也失败）仍可能丢消息——这一点在面试中要主动讲出来。
 *
 * @author xiongxiaoyang (二次开发)
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class AmqpMsgManager {

    private final RabbitTemplate rabbitTemplate;

    private final ObjectMapper objectMapper;

    private final StringRedisTemplate stringRedisTemplate;

    @Value("${spring.amqp.enabled:false}")
    private boolean amqpEnabled;

    /**
     * 注册生产者确认 / 不可路由回调。
     * RabbitTemplate 是单例，ConfirmCallback 全局只能设置一个，项目里只有本类使用，无冲突。
     */
    @PostConstruct
    public void initCallbacks() {
        rabbitTemplate.setConfirmCallback((correlationData, ack, cause) -> {
            if (ack || !(correlationData instanceof MessageCorrelationData data)) {
                return;
            }
            // 事务此刻已经提交，回滚数据是不可能的，只能留痕等待补偿重放
            stringRedisTemplate.opsForList().leftPush(
                AmqpConsts.BookChangeMq.FAILED_SEND_LIST_KEY, data.getPayload());
            log.error("[MQ生产者] 消息未被 broker 确认，已写入待重放列表 payload={} cause={}",
                data.getPayload(), cause);
        });
        rabbitTemplate.setReturnsCallback(returned -> log.error(
            "[MQ生产者] 消息无法路由到任何队列 exchange={} routingKey={} payload={}",
            returned.getExchange(), returned.getRoutingKey(),
            new String(returned.getMessage().getBody(), StandardCharsets.UTF_8)));
    }

    /**
     * 发送小说信息改变消息。
     *
     * @param bookId 发生变更的小说 ID；消费端据此回查最新数据并更新 ES / 清缓存
     */
    @SneakyThrows
    public void sendBookChangeMsg(Long bookId) {
        if (!amqpEnabled) {
            // 允许本机不启动 RabbitMQ 也能跑通发布章节；此时 ES 依赖 XXL-JOB 全量兜底
            log.warn("[MQ生产者] spring.amqp.enabled=false，跳过投递 bookId={}", bookId);
            return;
        }
        // msgId 是消费端幂等去重的依据；幂等键本身不代表业务顺序，业务数据一律以回查 DB 为准
        BookChangeMsg msg = new BookChangeMsg(
            UUID.randomUUID().toString(), bookId, "BOOK_INFO_CHANGE", System.currentTimeMillis());
        String payload = objectMapper.writeValueAsString(msg);
        log.info("[MQ生产者] 准备投递小说变更消息 bookId={} msgId={}", bookId, msg.getMsgId());
        sendAmqpMessage(payload);
    }

    private void sendAmqpMessage(String payload) {
        MessageCorrelationData correlationData = new MessageCorrelationData(payload);
        if (TransactionSynchronizationManager.isActualTransactionActive()) {
            // 关键：只有本地事务成功提交后才投递，避免「消息说章节已发布，但 DB 回滚了」
            TransactionSynchronizationManager.registerSynchronization(
                new TransactionSynchronization() {
                    @Override
                    public void afterCommit() {
                        rabbitTemplate.convertAndSend(AmqpConsts.BookChangeMq.EXCHANGE_NAME,
                            "", payload, correlationData);
                    }
                });
            return;
        }
        rabbitTemplate.convertAndSend(AmqpConsts.BookChangeMq.EXCHANGE_NAME,
            "", payload, correlationData);
    }

    /**
     * 自定义 CorrelationData，把原始消息体带进 confirm 回调，失败时才知道该重放什么。
     */
    private static class MessageCorrelationData extends CorrelationData {

        private final String payload;

        MessageCorrelationData(String payload) {
            super(UUID.randomUUID().toString());
            this.payload = payload;
        }

        String getPayload() {
            return payload;
        }
    }

}
