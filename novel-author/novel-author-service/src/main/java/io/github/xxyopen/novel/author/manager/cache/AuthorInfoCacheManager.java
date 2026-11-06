package io.github.xxyopen.novel.author.manager.cache;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import io.github.xxyopen.novel.author.dao.entity.AuthorInfo;
import io.github.xxyopen.novel.author.dao.mapper.AuthorInfoMapper;
import io.github.xxyopen.novel.author.dto.AuthorInfoDto;
import io.github.xxyopen.novel.common.constant.CacheConsts;
import io.github.xxyopen.novel.common.constant.DatabaseConsts;
import lombok.RequiredArgsConstructor;
import org.springframework.cache.annotation.CacheEvict;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.stereotype.Component;

import java.util.Objects;

/**
 * 作家信息 缓存管理类。
 *
 * <p>【业务与选型】作家专区几乎每个写操作都要先确认「当前 userId 是不是已入驻的作家」以及
 * 其笔名、状态，属于读多写少的高频校验数据；放在 Redis 远程缓存（ttl 48h、跨实例共享），
 * 避免每次请求都回查 author_info 表，也让多实例看到同一份数据。
 * 缓存 key 是 userId（方法参数），{@code unless = "#result == null"} 表示查不到作家时不缓存空值，
 * 防止用户刚提交入驻申请、库里已有数据却因为之前的空缓存一直被判为「未入驻」。
 *
 * @author xiongxiaoyang
 * @date 2022/5/12
 */
@Component
@RequiredArgsConstructor
public class AuthorInfoCacheManager {

    private final AuthorInfoMapper authorInfoMapper;

    /**
     * 按 userId 查询作家信息并缓存；查不到返回 null（由调用方转成「无权访问作家专区」）。
     * 结果是精简的 AuthorInfoDto（id / 笔名 / 状态），只放鉴权与展示必需的字段，不缓存整张表实体。
     */
    @Cacheable(cacheManager = CacheConsts.REDIS_CACHE_MANAGER,
        value = CacheConsts.AUTHOR_INFO_CACHE_NAME, unless = "#result == null")
    public AuthorInfoDto getAuthor(Long userId) {
        QueryWrapper<AuthorInfo> queryWrapper = new QueryWrapper<>();
        queryWrapper
            .eq(DatabaseConsts.AuthorInfoTable.COLUMN_USER_ID, userId)
            .last(DatabaseConsts.SqlEnum.LIMIT_1.getSql());
        AuthorInfo authorInfo = authorInfoMapper.selectOne(queryWrapper);
        if (Objects.isNull(authorInfo)) {
            return null;
        }
        return AuthorInfoDto.builder()
            .id(authorInfo.getId())
            .penName(authorInfo.getPenName())
            .status(authorInfo.getStatus()).build();
    }

    /**
     * 清除作家信息缓存。方法体留空，真正干活的是 {@code @CacheEvict} 切面。
     *
     * <p>【为什么必须 allEntries = true】getAuthor 的缓存 key 是 userId，
     * 而本方法没有任何参数：{@code @CacheEvict} 既不带 key、也不带 allEntries 时，
     * 默认会按 SimpleKey.EMPTY 作为 key 去删，永远删不掉那些以 userId 为键的条目——
     * 表现为「作家改了笔名 / 状态，接口还是返回旧值」，是一个很隐蔽的缓存一致性 bug。
     * 作家信息变更是低频操作，直接清空整个 authorInfoCache 最省心，
     * 代价只是下次访问各自回查一次库重建缓存，完全可接受。
     * 若想精确失效，应把 userId 作为方法参数传进来当 key，而不是清空全部。
     */
    @CacheEvict(cacheManager = CacheConsts.REDIS_CACHE_MANAGER,
        value = CacheConsts.AUTHOR_INFO_CACHE_NAME, allEntries = true)
    public void evictAuthorCache() {
        // 由 @CacheEvict 完成清理，方法体无需逻辑
    }

}
