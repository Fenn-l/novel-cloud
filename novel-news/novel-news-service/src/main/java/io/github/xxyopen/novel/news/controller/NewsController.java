package io.github.xxyopen.novel.news.controller;

import io.github.xxyopen.novel.common.constant.ApiRouterConsts;
import io.github.xxyopen.novel.common.resp.RestResp;
import io.github.xxyopen.novel.news.dto.resp.NewsInfoRespDto;
import io.github.xxyopen.novel.news.service.NewsService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 前台门户-新闻模块 API 控制器。
 *
 * <p>职责：暴露新闻的两个读接口——最新新闻列表、按 id 查详情。只做路由转发与参数绑定
 * （id 走 @PathVariable），缓存、判空、实体转换都在 Service / CacheManager，Controller
 * 不做重复校验；返回统一用 {@link RestResp} 包装。
 *
 * <p>设计取舍：新闻对游客开放，接口无鉴权；详情采用 RESTful 的 /{id} 形式，id 不存在
 * 时由 Service 转成业务错误码，Controller 保持无状态。
 *
 * @author xiongxiaoyang
 * @date 2022/5/12
 */
@Tag(name = "NewsController", description = "前台门户-新闻模块")
@RestController
@RequestMapping(ApiRouterConsts.API_FRONT_NEWS_URL_PREFIX)
@RequiredArgsConstructor
public class NewsController {

    private final NewsService newsService;

    /**
     * 最新新闻列表查询接口。
     *
     * <p>业务目的：给首页/新闻页提供引流用的最新两条，无入参、命中本地缓存，允许返回
     * 空列表（暂无新闻时前端隐藏模块）。
     */
    @Operation(summary = "最新新闻列表查询接口")
    @GetMapping("latest_list")
    public RestResp<List<NewsInfoRespDto>> listLatestNews() {
        return newsService.listLatestNews();
    }

    /**
     * 新闻信息查询接口。
     *
     * <p>业务目的：列表页点进详情时按 id 补齐标题、来源、更新时间与正文；id 由路径传入。
     *
     * <p>边界：id 不存在或正文缺失时，由 Service 抛 USER_REQUEST_PARAM_ERROR，前端按
     * 错误码提示即可，不会拿到半截数据或 500。
     *
     * @param id 新闻ID
     * @return 新闻信息
     */
    @Operation(summary = "新闻信息查询接口")
    @GetMapping("{id}")
    public RestResp<NewsInfoRespDto> getNews(
        @Parameter(description = "新闻ID") @PathVariable Long id) {
        return newsService.getNews(id);
    }
}
