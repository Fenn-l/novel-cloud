package io.github.xxyopen.novel.search.config;

import io.github.xxyopen.novel.common.constant.AmqpConsts;
import org.springframework.amqp.core.Binding;
import org.springframework.amqp.core.BindingBuilder;
import org.springframework.amqp.core.DirectExchange;
import org.springframework.amqp.core.FanoutExchange;
import org.springframework.amqp.core.Queue;
import org.springframework.amqp.core.QueueBuilder;
import org.springframework.amqp.rabbit.annotation.EnableRabbit;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * search 服务的 AMQP 拓扑：交换机、ES 增量队列、死信交换机与死信队列。
 *
 * <p>为什么这份配置不放在公共包 {@code novel-config} 里：
 * 所有业务服务的启动类都用了 {@code scanBasePackages = "io.github.xxyopen.novel"}，
 * 公共包里的 Queue / Exchange / @EnableRabbit 会被 <b>全部 8 个服务</b> 扫描并声明，
 * 导致 home、news、author、resource 这些与 MQ 毫无关系的服务也必须连上 RabbitMQ 才能干净启动
 * （旧版日志里每个服务约 33 次 AmqpConnectException）。
 * 现在拆成「谁消费谁声明」：只有 book 和 search 两个服务持有 MQ 拓扑，其余服务零 MQ 依赖。
 * AMQP 的 exchange/queue 声明是幂等的，两个服务各自声明同名 fanout 交换机没有冲突。
 *
 * @author xiongxiaoyang (二次开发)
 */
@Configuration
@EnableRabbit
public class BookEsAmqpConfig {

    /** 事件广播交换机；book 服务的缓存广播队列也绑定在同一个交换机上 */
    @Bean
    public FanoutExchange bookChangeExchange() {
        return new FanoutExchange(AmqpConsts.BookChangeMq.EXCHANGE_NAME, true, false);
    }

    /** 死信交换机：消费失败且不 requeue 的消息统一路由到这里 */
    @Bean
    public DirectExchange bookChangeDlx() {
        return new DirectExchange(AmqpConsts.BookChangeMq.DLX_EXCHANGE_NAME, true, false);
    }

    /** ES 增量更新队列（单点消费即可，索引是共享的外部存储） */
    @Bean
    public Queue esBookUpdateQueue() {
        return QueueBuilder.durable(AmqpConsts.BookChangeMq.QUEUE_ES_UPDATE)
            .deadLetterExchange(AmqpConsts.BookChangeMq.DLX_EXCHANGE_NAME)
            .deadLetterRoutingKey(AmqpConsts.BookChangeMq.DLQ_ROUTING_KEY)
            .build();
    }

    /** 死信队列：消息在此留痕，等待人工/补偿任务重放 */
    @Bean
    public Queue bookChangeDlq() {
        return QueueBuilder.durable(AmqpConsts.BookChangeMq.DLQ_NAME).build();
    }

    // 这里直接调用 @Bean 方法而不是按参数注入：本类有两个 Queue 类型的 Bean
    // （业务队列 + 死信队列），按类型注入会歧义；@Configuration 类被 CGLIB 增强后，
    // 方法调用返回的是容器里的同一个单例，不会重复创建。
    @Bean
    public Binding esBookUpdateQueueBinding() {
        return BindingBuilder.bind(esBookUpdateQueue()).to(bookChangeExchange());
    }

    @Bean
    public Binding bookChangeDlqBinding() {
        return BindingBuilder.bind(bookChangeDlq()).to(bookChangeDlx())
            .with(AmqpConsts.BookChangeMq.DLQ_ROUTING_KEY);
    }

}
