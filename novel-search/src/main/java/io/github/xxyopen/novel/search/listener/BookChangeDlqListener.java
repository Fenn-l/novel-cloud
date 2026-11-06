package io.github.xxyopen.novel.search.listener;

import io.github.xxyopen.novel.common.constant.AmqpConsts;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

/**
 * 死信队列消费者：对 ES 增量同步失败的消息留痕，供人工核查或补偿任务重放。
 *
 * <p>早期版本只打一行 error 日志，消息 ack 之后就彻底消失，DLQ 成了"死胡同"。
 * 现在把原始 payload 写入 Redis 列表（只保留最近 200 条，防止无限膨胀），
 * 运维可以直接读取重放，也可以接告警系统。
 *
 * <p>诚实边界：这里仍没有「自动重放」，生产级应在此基础上加定时任务 + 重试次数上限
 * + 超过阈值告警；本项目另有 XXL-JOB 全量同步作为最终兜底。
 *
 * @author xiongxiaoyang (二次开发)
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class BookChangeDlqListener {

    private static final int MAX_RECORDS = 200;

    private final StringRedisTemplate stringRedisTemplate;

    @RabbitListener(queues = AmqpConsts.BookChangeMq.DLQ_NAME)
    public void onDeadLetter(String payload) {
        try {
            stringRedisTemplate.opsForList()
                .leftPush(AmqpConsts.BookChangeMq.DLQ_RECORD_LIST_KEY, payload);
            stringRedisTemplate.opsForList()
                .trim(AmqpConsts.BookChangeMq.DLQ_RECORD_LIST_KEY, 0, MAX_RECORDS - 1);
        } catch (Exception e) {
            log.error("[死信队列] 留痕失败 payload={} err={}", payload, e.getMessage(), e);
        }
        log.error("[死信队列] 收到失败消息，已留痕待重放：{}", payload);
    }

}
