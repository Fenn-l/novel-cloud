package io.github.xxyopen.novel.book.config;

import io.github.xxyopen.novel.common.constant.AmqpConsts;
import org.springframework.amqp.core.AnonymousQueue;
import org.springframework.amqp.core.Binding;
import org.springframework.amqp.core.BindingBuilder;
import org.springframework.amqp.core.FanoutExchange;
import org.springframework.amqp.rabbit.annotation.EnableRabbit;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.UUID;

/**
 * book 服务的 AMQP 拓扑：为「本地缓存失效」声明一个 <b>每实例独占</b> 的匿名队列。
 *
 * <p>这是本项目缓存一致性最关键的一处设计：
 * 小说信息用的是 Caffeine <b>进程内</b>缓存，book 服务多实例部署时每个 JVM 各有一份。
 * 如果所有实例共用一个持久队列，fanout 发出的一条消息会被其中某一个实例竞争消费，
 * 结果只有它清了自己的缓存，其他实例在长达 18 小时的 TTL 内仍返回旧的 lastChapterId。
 *
 * <p>改成 AnonymousQueue（非持久 / exclusive / autoDelete）后，
 * 每个实例启动时各自声明一个随机名字的队列并绑定到 fanout 交换机，
 * 一条消息会复制投递到 <b>所有</b> 实例的队列，每个实例清理自己的那份本地缓存。
 * 实例下线时队列自动删除，不会堆积无人消费的消息。
 *
 * <p>代价：这个队列不能配 DLX 做重放（队列随实例消失），
 * 因此缓存清理采用「尽力而为」策略，失败仅记日志，由本地缓存 TTL 兜底。
 *
 * @author xiongxiaoyang (二次开发)
 */
@Configuration
@EnableRabbit
public class BookCacheAmqpConfig {

    /** 与 search 服务声明的是同一个持久化 fanout 交换机，AMQP 声明幂等 */
    @Bean
    public FanoutExchange bookChangeExchange() {
        return new FanoutExchange(AmqpConsts.BookChangeMq.EXCHANGE_NAME, true, false);
    }

    @Bean
    public AnonymousQueue bookCacheBroadcastQueue() {
        return new AnonymousQueue(() ->
            AmqpConsts.BookChangeMq.CACHE_BROADCAST_QUEUE_PREFIX + UUID.randomUUID());
    }

    @Bean
    public Binding bookCacheBroadcastBinding() {
        // fanout 绑定忽略 routingKey；直接调用 @Bean 方法取容器单例，避免参数注入歧义
        return BindingBuilder.bind(bookCacheBroadcastQueue()).to(bookChangeExchange());
    }

}
