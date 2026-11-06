package io.github.xxyopen.novel.config;

import com.github.benmanes.caffeine.cache.Caffeine;
import io.github.xxyopen.novel.common.constant.CacheConsts;

import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.cache.CacheManager;
import org.springframework.cache.caffeine.CaffeineCache;
import org.springframework.cache.support.SimpleCacheManager;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;
import org.springframework.data.redis.cache.RedisCacheConfiguration;
import org.springframework.data.redis.cache.RedisCacheManager;
import org.springframework.data.redis.cache.RedisCacheWriter;
import org.springframework.data.redis.connection.RedisConnectionFactory;

/**
 * 两级缓存装配：Caffeine（本地 / 进程内） + Redis（远程 / 集中式）。
 *
 * <p>【分工】缓存清单集中在 {@link CacheConsts.CacheEnum}，每项用 type 标注归属：
 * 0=仅本地、1=本地+远程、2=仅远程。
 * - 本地 Caffeine：数据在 JVM 堆内，零网络开销、读取极快，适合首页推荐、榜单、分类这类
 *   高频热点且体量可控的数据；用 maximumSize 限制条数防止堆内存被撑爆。
 *   代价是每个服务实例各存一份，实例之间不共享，数据变更要靠 MQ 广播清理或等 TTL 自然过期。
 * - 远程 Redis：集中存储、多实例共享，适合用户信息、作家信息、书籍正文这类需要在实例间
 *   保持一致、或体量太大不适合全量放本地的数据。
 * 业务代码通过 {@code @Cacheable(cacheManager = ...)} 显式选用哪一级；而 type 决定
 * 「往哪些 CacheManager 里预先注册同名 Cache」，保证两边都能按名字拿到 TTL / 容量配置，
 * 避免运行时动态创建的缓存丢失这些设置。
 *
 * <p>【为什么 caffeineCacheManager 要 @Primary】容器里同时存在两个 CacheManager Bean，
 * 一旦某处 {@code @Cacheable} 省略了 cacheManager、或自动配置要挑一个默认 CacheManager，
 * 就会因为「候选不唯一」抛 NoUniqueBeanDefinitionException。标 {@code @Primary} 后，
 * 未显式指定的场景统一落到本地缓存（读多写少的热点走本地性能最好），
 * 而显式写了 REDIS_CACHE_MANAGER 的注解完全不受影响。
 *
 * @author xiongxiaoyang
 * @date 2022/5/12
 */
@Configuration
public class CacheConfig {

    /**
     * 本地缓存管理器：为 {@code CacheEnum} 中 isLocal() 的每一项构建一个 CaffeineCache。
     *
     * <p>【ttl = 0 的语义】只有 ttl &gt; 0 才调用 expireAfterWrite；等于 0 时完全不设过期，
     * 也就是「永不失效」。这类缓存（如分类列表）变更极少，省去过期重建的开销；
     * 但代价是没有任何自动失效入口，数据一旦变更就必须显式 {@code @CacheEvict} 或重启实例，
     * 否则各实例会一直返回旧值——首页友情链接缓存后来改成 24h 兜底，正是踩了这个坑。
     * recordStats() 打开命中率统计，方便本地压测与容量调优。
     */
    @Bean
    @Primary
    public CacheManager caffeineCacheManager() {
        SimpleCacheManager cacheManager = new SimpleCacheManager();

        List<CaffeineCache> caches = new ArrayList<>(CacheConsts.CacheEnum.values().length);
        for (var c : CacheConsts.CacheEnum.values()) {
            if (c.isLocal()) {
                Caffeine<Object, Object> caffeine = Caffeine.newBuilder().recordStats()
                    .maximumSize(c.getMaxSize());
                if (c.getTtl() > 0) {
                    caffeine.expireAfterWrite(Duration.ofSeconds(c.getTtl()));
                }
                caches.add(new CaffeineCache(c.getName(), caffeine.build()));
            }
        }

        cacheManager.setCaches(caches);
        return cacheManager;
    }

    /**
     * 远程缓存管理器：为 {@code CacheEnum} 中 isRemote() 的每一项配置独立的 TTL。
     * 缓存名统一加前缀 {@code Cache::Novel::} 做命名空间隔离，避免与其它应用 / 环境串 key；
     * disableCachingNullValues() 表示不缓存 null，防止「当时查不到」被缓存后，
     * 数据已新增却仍长期读到空值。
     *
     * <p>【ttl = 0 的语义】与本地一致：只有 ttl &gt; 0 才设置 entryTtl，否则沿用
     * defaultCacheConfig（不带过期时间），即永不失效。
     *
     * <p>【transactionAware = true 的含义】让缓存的 put / evict 与 Spring 事务同步：
     * 操作先挂起，等事务真正提交后才写入 / 删除 Redis；事务回滚则缓存操作一并撤销。
     * 这样不会出现「事务回滚了但缓存已被清掉或已写入」导致的脏数据。
     * 默认 false 时缓存操作立即生效、与事务无关，在「先写库再失效缓存」的组合里容易不一致。
     * 代价是缓存变更有轻微延迟（要等提交），对本项目这种读多写少的场景可以接受。
     */
    @Bean
    public CacheManager redisCacheManager(RedisConnectionFactory connectionFactory) {
        RedisCacheWriter redisCacheWriter = RedisCacheWriter.nonLockingRedisCacheWriter(
            connectionFactory);

        RedisCacheConfiguration defaultCacheConfig = RedisCacheConfiguration.defaultCacheConfig()
            .disableCachingNullValues().prefixCacheNameWith(CacheConsts.REDIS_CACHE_PREFIX);

        Map<String, RedisCacheConfiguration> cacheMap = new LinkedHashMap<>(
            CacheConsts.CacheEnum.values().length);
        for (var c : CacheConsts.CacheEnum.values()) {
            if (c.isRemote()) {
                if (c.getTtl() > 0) {
                    cacheMap.put(c.getName(),
                        RedisCacheConfiguration.defaultCacheConfig().disableCachingNullValues()
                            .prefixCacheNameWith(CacheConsts.REDIS_CACHE_PREFIX)
                            .entryTtl(Duration.ofSeconds(c.getTtl())));
                } else {
                    cacheMap.put(c.getName(),
                        RedisCacheConfiguration.defaultCacheConfig().disableCachingNullValues()
                            .prefixCacheNameWith(CacheConsts.REDIS_CACHE_PREFIX));
                }
            }
        }

        RedisCacheManager redisCacheManager = new RedisCacheManager(redisCacheWriter,
            defaultCacheConfig, cacheMap);
        redisCacheManager.setTransactionAware(true);
        redisCacheManager.initializeCaches();
        return redisCacheManager;
    }

}
