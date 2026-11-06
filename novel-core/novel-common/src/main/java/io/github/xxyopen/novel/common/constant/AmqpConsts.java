package io.github.xxyopen.novel.common.constant;

/**
 * 「小说信息变更」事件的 AMQP 拓扑常量。
 *
 * <p>拓扑（详见 doc/面试讲解.md）：
 * <pre>
 *   发布章节(事务提交后) ──> EXCHANGE-BOOK-CHANGE (fanout)
 *                              ├─ QUEUE-ES-BOOK-UPDATE      (search 服务消费：增量更新 ES 索引)
 *                              │      └─ 失败 ──> DLX ──> QUEUE-BOOK-CHANGE-DLQ
 *                              └─ QUEUE-BOOK-CACHE-BROADCAST-* (book 服务「每实例一个」匿名独占队列)
 *                                     └─ 清各实例自己的 Caffeine 本地缓存
 * </pre>
 *
 * <p>为什么缓存失效用「每实例独占队列」而不是一个共享队列：
 * 共享队列是 competing consumer（竞争消费），一条消息只会被某一个实例取走，
 * 于是只有那一个实例清了自己的本地缓存，其余实例继续返回旧数据。
 * fanout + 每实例独占队列才能让广播真正到达所有实例。
 *
 * @author xiongxiaoyang (二次开发)
 */
public class AmqpConsts {

    /**
     * 小说信息改变 MQ
     */
    public static class BookChangeMq {

        /** 小说信息改变交换机（fanout 广播；fanout 下 routingKey 无意义，固定传空串） */
        public static final String EXCHANGE_NAME = "EXCHANGE-BOOK-CHANGE";

        /** 死信交换机（direct） */
        public static final String DLX_EXCHANGE_NAME = "EXCHANGE-BOOK-CHANGE-DLX";

        /** 死信队列 */
        public static final String DLQ_NAME = "QUEUE-BOOK-CHANGE-DLQ";

        /** 死信路由键 */
        public static final String DLQ_ROUTING_KEY = "book.change.dlq";

        /** ES 索引增量更新队列（search 服务唯一消费，可配 DLX） */
        public static final String QUEUE_ES_UPDATE = "QUEUE-ES-BOOK-UPDATE";

        /** book 服务每个实例匿名独占队列的名字前缀 */
        public static final String CACHE_BROADCAST_QUEUE_PREFIX = "QUEUE-BOOK-CACHE-BROADCAST-";

        /** 生产者 confirm 失败时写入的待重放列表（事务已提交，无法回滚，只能补偿） */
        public static final String FAILED_SEND_LIST_KEY = "book:mq:send:failed";

        /** 死信消息留痕列表，供人工核查/重放 */
        public static final String DLQ_RECORD_LIST_KEY = "book:mq:dlq:records";

        /** ES 消费者幂等键前缀（拼接 msgId） */
        public static final String DEDUP_KEY_PREFIX_ES = "book:mq:es:";

        /** 幂等键有效期：覆盖正常重试窗口即可，不宜过长 */
        public static final long DEDUP_TTL_MINUTES = 10L;

    }

}
