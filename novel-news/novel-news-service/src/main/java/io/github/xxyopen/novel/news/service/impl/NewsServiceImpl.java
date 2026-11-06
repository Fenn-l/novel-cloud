package io.github.xxyopen.novel.news.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import io.github.xxyopen.novel.common.constant.DatabaseConsts;
import io.github.xxyopen.novel.common.constant.ErrorCodeEnum;
import io.github.xxyopen.novel.common.resp.RestResp;
import io.github.xxyopen.novel.config.exception.BusinessException;
import io.github.xxyopen.novel.news.dao.entity.NewsContent;
import io.github.xxyopen.novel.news.dao.entity.NewsInfo;
import io.github.xxyopen.novel.news.dao.mapper.NewsContentMapper;
import io.github.xxyopen.novel.news.dao.mapper.NewsInfoMapper;
import io.github.xxyopen.novel.news.dto.resp.NewsInfoRespDto;
import io.github.xxyopen.novel.news.manager.cache.NewsCacheManager;
import io.github.xxyopen.novel.news.service.NewsService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * 新闻模块 服务实现类。
 *
 * <p>职责：对外提供「最新新闻列表」和「单条新闻详情」两个读能力，并把 DAO 实体转换成
 * 前端用的 NewsInfoRespDto。列表走缓存、详情直查库，是本类最核心的设计分叉。
 *
 * <p>设计取舍：新闻拆成两张表——news_info 存标题/来源/分类/时间等列表要用的轻字段，
 * news_content 单独存大段正文。列表与首页只查 news_info、不拖正文；只有点进详情才按
 * news_id 回查正文。这样既减小列表查询的数据量，也让「最新两条」这种高频查询能被
 * 小体积缓存完整装下，避免大字段污染缓存。
 *
 * <p>边界：详情按 id 查询，id 可能不存在，新闻正文也可能被单独删除导致数据不完整。
 * 两种情况都必须显式判空并转成业务错误，不能让空指针冒泡成 500。
 *
 * @author xiongxiaoyang
 * @date 2022/5/14
 */
@Service
@RequiredArgsConstructor
public class NewsServiceImpl implements NewsService {

    private final NewsCacheManager newsCacheManager;

    private final NewsInfoMapper newsInfoMapper;

    private final NewsContentMapper newsContentMapper;

    /**
     * 查询最新发布的新闻列表。
     *
     * <p>业务目的：首页/新闻页只需要最新两条做引流，属于高频读、低时效敏感的场景，
     * 因此整体交给 NewsCacheManager 的本地缓存，Service 不重复查库、不做二次加工。
     *
     * <p>边界：暂无新闻时返回空列表，前端隐藏该模块即可，不视为错误。
     *
     * @return 新闻列表
     */
    @Override
    public RestResp<List<NewsInfoRespDto>> listLatestNews() {
        return RestResp.ok(newsCacheManager.listLatestNews());
    }

    /**
     * 按 ID 查询单条新闻详情（标题 + 正文）。
     *
     * <p>业务目的：列表页只给出 id，点进详情时再补齐标题、来源、更新时间与正文。正文在
     * 单独的大字段表里，用 news_id 精确查一条（limit 1）即可。
     *
     * <p>为什么不走缓存：详情 id 离散、长尾访问多，缓存命中率低却要占用大量内存，
     * 不如直接查库；这与「最新两条列表」的缓存策略刻意不同。
     *
     * <p>边界：id 不存在时 newsInfo 为 null；新闻正文缺失时 newsContent 为 null（数据
     * 不完整）。两者都属于「请求了不存在/不完整的数据」，统一按 USER_REQUEST_PARAM_ERROR
     * 抛业务异常，避免直接 .getTitle() / .getContent() 触发 NPE。
     *
     * @param id 新闻ID
     * @return 新闻信息
     */
    @Override
    public RestResp<NewsInfoRespDto> getNews(Long id) {
        NewsInfo newsInfo = newsInfoMapper.selectById(id);
        // 边界：id 指向的新闻不存在，直接转成参数错误，不让后续取值空指针
        if (newsInfo == null) {
            throw new BusinessException(ErrorCodeEnum.USER_REQUEST_PARAM_ERROR);
        }
        QueryWrapper<NewsContent> queryWrapper = new QueryWrapper<>();
        queryWrapper.eq(DatabaseConsts.NewsContentTable.COLUMN_NEWS_ID, id)
            .last(DatabaseConsts.SqlEnum.LIMIT_1.getSql());
        NewsContent newsContent = newsContentMapper.selectOne(queryWrapper);
        // 边界：新闻存在但正文被删/未发布，同样按参数错误返回，避免返回半截数据
        if (newsContent == null) {
            throw new BusinessException(ErrorCodeEnum.USER_REQUEST_PARAM_ERROR);
        }
        return RestResp.ok(NewsInfoRespDto.builder()
            .title(newsInfo.getTitle())
            .sourceName(newsInfo.getSourceName())
            .updateTime(newsInfo.getUpdateTime())
            .content(newsContent.getContent())
            .build());
    }
}
