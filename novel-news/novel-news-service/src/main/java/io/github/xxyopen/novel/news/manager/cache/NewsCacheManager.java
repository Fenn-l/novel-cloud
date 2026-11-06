package io.github.xxyopen.novel.news.manager.cache;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import io.github.xxyopen.novel.common.constant.CacheConsts;
import io.github.xxyopen.novel.common.constant.DatabaseConsts;
import io.github.xxyopen.novel.news.dao.entity.NewsInfo;
import io.github.xxyopen.novel.news.dao.mapper.NewsInfoMapper;
import io.github.xxyopen.novel.news.dto.resp.NewsInfoRespDto;
import lombok.RequiredArgsConstructor;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 新闻 缓存管理类。
 *
 * <p>职责：只缓存「最新两条新闻」这一个列表。新闻是典型的读多写少内容，首页每次打开
 * 都要读它，用 Caffeine 本地缓存 10 分钟即可挡住绝大部分请求。
 *
 * <p>设计取舍：这里只放列表、不放详情。列表条数固定（2 条）、体积小、命中率高；详情
 * id 离散且正文很大，缓存收益低、内存成本高，所以由 Service 直接查库。缓存里也只存
 * 列表需要的轻字段（不含正文），避免大对象进本地缓存。
 *
 * <p>边界：新闻发布/下线后最多 10 分钟才反映到首页，属于可接受的最终一致；当前没有
 * 主动失效入口，靠 TTL 自然过期，这是用「短暂不一致」换「零维护成本」的取舍。
 *
 * @author xiongxiaoyang
 * @date 2022/5/12
 */
@Component
@RequiredArgsConstructor
public class NewsCacheManager {

    private final NewsInfoMapper newsInfoMapper;

    /**
     * 查询最新发布的两条新闻并放入缓存。
     *
     * <p>业务目的：给首页/新闻页提供引流用的最新两条，按创建时间倒序取，只映射列表
     * 需要的字段（id、分类、标题、来源、更新时间），不带正文。
     *
     * <p>边界：表中暂无新闻时返回空列表；排序 + LIMIT 2 由 SQL 完成，不在内存里截断，
     * 避免把整表数据捞进应用再裁剪。
     *
     * @return 最新两条新闻列表
     */
    @Cacheable(cacheManager = CacheConsts.CAFFEINE_CACHE_MANAGER,
        value = CacheConsts.LATEST_NEWS_CACHE_NAME)
    public List<NewsInfoRespDto> listLatestNews() {
        // 从新闻信息表中查询出最新发布的两条新闻
        QueryWrapper<NewsInfo> queryWrapper = new QueryWrapper<>();
        queryWrapper.orderByDesc(DatabaseConsts.CommonColumnEnum.CREATE_TIME.getName())
            .last(DatabaseConsts.SqlEnum.LIMIT_2.getSql());
        return newsInfoMapper.selectList(queryWrapper).stream().map(v -> NewsInfoRespDto.builder()
            .id(v.getId())
            .categoryId(v.getCategoryId())
            .categoryName(v.getCategoryName())
            .title(v.getTitle())
            .sourceName(v.getSourceName())
            .updateTime(v.getUpdateTime())
            .build()).toList();
    }

}
