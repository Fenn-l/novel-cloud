package io.github.xxyopen.novel.search.listener;

import co.elastic.clients.elasticsearch.ElasticsearchClient;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.xxyopen.novel.book.dto.resp.BookEsRespDto;
import io.github.xxyopen.novel.common.constant.AmqpConsts;
import io.github.xxyopen.novel.common.dto.BookChangeMsg;
import io.github.xxyopen.novel.search.constant.EsConsts;
import io.github.xxyopen.novel.search.manager.feign.BookFeignManager;
import lombok.RequiredArgsConstructor;
import lombok.SneakyThrows;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.List;
import java.util.Objects;

/**
 * 消费者一：监听小说信息变更消息，增量更新 ES 的 book 索引。
 *
 * <p>设计要点：
 * <ul>
 *   <li><b>消息只带 bookId，不带业务字段</b>：消费时通过 Feign 回查 book 服务的最新数据。
 *       这样即使消息乱序或延迟，写进 ES 的也始终是 DB 当前值，天然避免"旧消息覆盖新数据"。</li>
 *   <li><b>幂等</b>：以 msgId 做 Redis setIfAbsent 去重；处理失败必须删除幂等键，
 *       否则死信重放会在 TTL 内被误判为重复而跳过。</li>
 *   <li><b>失败不放过</b>：取不到数据 / Feign 失败 / ES 写入失败，一律抛异常，
 *       由容器 reject(requeue=false) 经 DLX 进入死信队列留痕。
 *       早期版本在这里 "warn + return"，等于把消息 ack 掉、ES 静默漏更新，是典型的一致性坑。</li>
 * </ul>
 *
 * @author xiongxiaoyang (二次开发)
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class BookChangeEsListener {

    private final BookFeignManager bookFeignManager;
    private final ElasticsearchClient elasticsearchClient;
    private final StringRedisTemplate stringRedisTemplate;
    private final ObjectMapper objectMapper;

    @SneakyThrows
    @RabbitListener(queues = AmqpConsts.BookChangeMq.QUEUE_ES_UPDATE)
    public void onBookChange(String payload) {
        BookChangeMsg msg = objectMapper.readValue(payload, BookChangeMsg.class);
        String dedupKey = AmqpConsts.BookChangeMq.DEDUP_KEY_PREFIX_ES + msg.getMsgId();
        Boolean first = stringRedisTemplate.opsForValue().setIfAbsent(dedupKey, "1",
            Duration.ofMinutes(AmqpConsts.BookChangeMq.DEDUP_TTL_MINUTES));
        if (!Boolean.TRUE.equals(first)) {
            log.info("[ES消费者] 重复消息，幂等跳过 msgId={}", msg.getMsgId());
            return;
        }
        try {
            Long bookId = msg.getBookId();
            if (bookId == null || bookId <= 0) {
                throw new IllegalArgumentException("非法 bookId=" + bookId);
            }
            // 以 bookId-1 为游标拉取下一批，再从结果里精确定位目标书
            List<BookEsRespDto> books = bookFeignManager.listEsBooks(bookId - 1);
            BookEsRespDto target = books.stream()
                .filter(b -> Objects.equals(b.getId(), bookId))
                .findFirst().orElse(null);
            if (target == null) {
                // 不静默跳过：可能是 Feign 降级返回空、书未达索引条件、或数据异常，
                // 交给死信队列留痕，由人工/补偿任务判断，避免"看起来成功但索引没更新"
                throw new IllegalStateException("未取到可索引小说 bookId=" + bookId
                    + "，触发死信留痕");
            }
            elasticsearchClient.index(i -> i
                .index(EsConsts.BookIndex.INDEX_NAME)
                .id(bookId.toString())
                .document(target));
            log.info("[ES消费者] 索引增量更新成功 bookId={} msgId={}", bookId, msg.getMsgId());
        } catch (Exception e) {
            // 关键：释放幂等键，允许死信重放
            stringRedisTemplate.delete(dedupKey);
            log.error("[ES消费者] 处理失败，将进入死信队列 msgId={} err={}",
                msg.getMsgId(), e.getMessage());
            throw e;
        }
    }

}
