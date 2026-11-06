package io.github.xxyopen.novel.book.listener;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.xxyopen.novel.book.manager.cache.BookInfoCacheManager;
import io.github.xxyopen.novel.common.dto.BookChangeMsg;
import lombok.RequiredArgsConstructor;
import lombok.SneakyThrows;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.stereotype.Component;

/**
 * 消费者二：广播清理各 book 实例的 Caffeine 本地缓存。
 *
 * <p>监听的是 {@code BookCacheAmqpConfig} 声明的「每实例独占匿名队列」，
 * 同一个 msgId 会在每个实例各被消费一次——这正是我们要的效果。
 *
 * <p><b>这里刻意不做 msgId 幂等去重</b>：如果用一个共享 Redis 键去重，
 * 就会退化成"只有第一个实例执行清理"，多实例下其它实例的本地缓存依旧是脏的。
 * 清理缓存本身就是幂等操作，重复执行没有任何副作用。
 *
 * @author xiongxiaoyang (二次开发)
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class BookChangeCacheListener {

    private final BookInfoCacheManager bookInfoCacheManager;
    private final ObjectMapper objectMapper;

    @SneakyThrows
    @RabbitListener(queues = "#{bookCacheBroadcastQueue.name}")
    public void onBookChange(String payload) {
        BookChangeMsg msg = objectMapper.readValue(payload, BookChangeMsg.class);
        try {
            bookInfoCacheManager.evictBookInfoCache(msg.getBookId());
            log.info("[缓存消费者] 已清除本实例小说信息缓存 bookId={} msgId={}",
                msg.getBookId(), msg.getMsgId());
        } catch (Exception e) {
            // 尽力而为：清缓存失败不影响 DB 数据的正确性，本地缓存 18h TTL 会自然过期。
            // 独占队列没有 DLX，这里不抛异常，避免消息被反复 requeue 或静默丢弃后无人知晓。
            log.error("[缓存消费者] 清理本地缓存失败，等待 TTL 兜底 bookId={} msgId={} err={}",
                msg.getBookId(), msg.getMsgId(), e.getMessage(), e);
        }
    }

}
