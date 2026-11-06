package io.github.xxyopen.novel.book;

import org.mybatis.spring.annotation.MapperScan;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.cache.annotation.EnableCaching;
import org.springframework.cloud.client.discovery.EnableDiscoveryClient;
import org.springframework.cloud.openfeign.EnableFeignClients;

@SpringBootApplication(scanBasePackages = {"io.github.xxyopen.novel"})
// scanBasePackages 需要放宽到 io.github.xxyopen.novel，才能扫描到 novel-core 里的公共 Bean
// （如 CacheConfig、LockAspect、全局异常处理器、TokenParseInterceptor 等）。
// 注意：MQ 拓扑刻意不放公共包，见本模块 config/BookCacheAmqpConfig 与
// search 模块 config/BookEsAmqpConfig ——「谁消费谁声明」，避免所有服务都强依赖 RabbitMQ。
@MapperScan("io.github.xxyopen.novel.book.dao.mapper")
@EnableCaching      // 开启 Spring Cache 注解（@Cacheable/@CachePut/@CacheEvict），缓存实现见 CacheConfig
@EnableDiscoveryClient           // 注册到 Nacos，供网关 lb://novel-book-service 发现
@EnableFeignClients(basePackages = {"io.github.xxyopen.novel.user.feign"}) // 声明要调用的 user 服务 Feign 客户端
public class NovelBookApplication {

    public static void main(String[] args) {
        SpringApplication.run(NovelBookApplication.class, args);
    }

}
