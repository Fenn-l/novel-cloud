package io.github.xxyopen.novel.home.controller.front;

import io.github.xxyopen.novel.common.constant.ApiRouterConsts;
import io.github.xxyopen.novel.common.resp.RestResp;
import io.github.xxyopen.novel.home.dto.resp.HomeBookRespDto;
import io.github.xxyopen.novel.home.dto.resp.HomeFriendLinkRespDto;
import io.github.xxyopen.novel.home.service.HomeService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 前台门户-首页模块 API 控制器。
 *
 * <p>职责：只做 HTTP 层，暴露首页的两个读接口（小说推荐、友情链接）。参数校验、缓存、
 * 跨服务数据拼装都不放在这里，统一交给 Service / CacheManager，保持 Controller 无状态、
 * 可替换；返回统一用 {@link RestResp} 包装，便于前端按同一套错误码处理。
 *
 * <p>设计取舍：首页数据对所有游客开放且内容完全相同，接口不带任何用户态参数、不加
 * 鉴权，这样响应才可以被 CDN / 浏览器缓存；两个接口都是幂等 GET，不产生副作用。
 *
 * @author xiongxiaoyang
 * @date 2022/5/12
 */
@Tag(name = "HomeController", description = "前台门户-首页模块")
@RestController
@RequestMapping(ApiRouterConsts.API_FRONT_HOME_URL_PREFIX)
@RequiredArgsConstructor
public class HomeController {

    private final HomeService homeService;

    /**
     * 首页小说推荐查询接口。
     *
     * <p>业务目的：门户首屏一次性拿全所有推荐位（轮播图、顶部栏、本周强推等），由前端
     * 按 type 分组渲染，因此返回的是不分页的整包列表，而不是按类型多次请求——减少首屏
     * 的请求往返。
     *
     * <p>边界：无入参、无鉴权；后台未配置推荐位时返回空列表，属正常结果，由前端隐藏
     * 对应模块即可，不视为错误。
     */
    @Operation(summary = "首页小说推荐查询接口")
    @GetMapping("books")
    public RestResp<List<HomeBookRespDto>> listHomeBooks() {
        return homeService.listHomeBooks();
    }

    /**
     * 首页友情链接列表查询接口。
     *
     * <p>业务目的：供页脚渲染运营维护的友链。数据准静态、读多写极少，命中 Redis 缓存时
     * 不查库；接口同样无入参、无鉴权，允许返回空列表。
     */
    @Operation(summary = "首页友情链接列表查询接口")
    @GetMapping("friend_Link/list")
    public RestResp<List<HomeFriendLinkRespDto>> listHomeFriendLinks() {
        return homeService.listHomeFriendLinks();
    }

}
