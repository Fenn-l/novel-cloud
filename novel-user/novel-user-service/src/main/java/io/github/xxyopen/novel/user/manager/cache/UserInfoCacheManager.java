package io.github.xxyopen.novel.user.manager.cache;

import io.github.xxyopen.novel.common.constant.CacheConsts;
import io.github.xxyopen.novel.user.dao.entity.UserInfo;
import io.github.xxyopen.novel.user.dao.mapper.UserInfoMapper;
import io.github.xxyopen.novel.user.dto.UserInfoDto;
import lombok.RequiredArgsConstructor;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.stereotype.Component;

import java.util.Objects;

/**
 * 用户信息 缓存管理类：供认证拦截器校验「当前 userId 对应的账号是否存在、状态是否正常」。
 *
 * <p>【业务与选型】几乎每个需要登录的接口都会经过 AuthInterceptor，解析出 JWT 里的 userId 后
 * 都要确认账号有效；这是请求链路上的高频读，若每次回查 user_info 表会白白增加数据库压力。
 * 因此按 userId 缓存在 Redis（ttl 24h、跨实例共享），精简成 UserInfoDto 只保留 id 和 status
 * ——鉴权只需要这两个字段，密码、手机号等敏感信息绝不进缓存，减小泄露面也减小 value 体积。
 *
 * <p>【查不到怎么办】账号不存在时返回 null。这里没有写 unless，但 CacheConfig 的
 * RedisCacheManager 启用了 disableCachingNullValues()，空值不会被写入 Redis，
 * 所以不会出现「用户当时不存在 → 空结果被缓存 → 之后注册了仍被判为不存在」的坑。
 *
 * @author xiongxiaoyang
 * @date 2022/5/12
 */
@Component
@RequiredArgsConstructor
public class UserInfoCacheManager {

    private final UserInfoMapper userInfoMapper;

    /**
     * 按 userId 查询精简用户信息并缓存，缓存 key 即 userId；查不到返回 null 由拦截器转成「账号不存在」。
     */
    @Cacheable(cacheManager = CacheConsts.REDIS_CACHE_MANAGER,
        value = CacheConsts.USER_INFO_CACHE_NAME)
    public UserInfoDto getUser(Long userId) {
        UserInfo userInfo = userInfoMapper.selectById(userId);
        if (Objects.isNull(userInfo)) {
            return null;
        }
        return UserInfoDto.builder()
            .id(userInfo.getId())
            .status(userInfo.getStatus()).build();
    }

}
