package io.github.xxyopen.novel.resource.controller;

import io.github.xxyopen.novel.common.constant.ApiRouterConsts;
import io.github.xxyopen.novel.common.resp.RestResp;
import io.github.xxyopen.novel.resource.dto.resp.ImgVerifyCodeRespDto;
import io.github.xxyopen.novel.resource.service.ResourceService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;

/**
 * 前台门户-资源(图片/验证码)模块 API 控制器。
 *
 * <p>职责：暴露两个入口——获取图形验证码、上传图片。只做路由与参数绑定，验证码的
 * Redis 写入、图片的落盘与真图片校验都在 Service / Manager / 拦截器，Controller 保持最薄。
 *
 * <p>设计取舍：两个接口都在游客态可用、无鉴权，因为注册前就要先拿验证码；上传用
 * multipart/form-data 的 @RequestParam("file")，返回 /image/** 相对路径而非完整 URL，
 * 由前端拼域名，便于多环境部署时不用改后端。
 *
 * <p>跨服务约定：验证码接口返回的 sessionId 必须在后续注册请求里原样带回，user 服务
 * 才会用同一个 Redis Key 校验答案。Controller 不参与校验，但要保证 sessionId 原样透出、
 * 不被吞掉。
 *
 * @author xiongxiaoyang
 * @date 2022/5/17
 */
@Tag(name = "ResourceController", description = "前台门户-资源模块")
@RestController
@RequestMapping(ApiRouterConsts.API_FRONT_RESOURCE_URL_PREFIX)
@RequiredArgsConstructor
public class ResourceController {

    private final ResourceService resourceService;

    /**
     * 获取图片验证码接口。
     *
     * <p>业务目的：注册前拉取一张验证码图片。响应里同时带 sessionId 与 Base64 图片，
     * 前端需暂存 sessionId，注册时连同用户输入的验证码一起提交给 user 服务校验。
     *
     * <p>边界：每次调用都生成新的 sessionId 与新答案，各自独立 Key、互不影响，直到
     * 5 分钟 TTL 到期；接口无鉴权、无入参。
     */
    @Operation(summary = "获取图片验证码接口")
    @GetMapping("img_verify_code")
    public RestResp<ImgVerifyCodeRespDto> getImgVerifyCode() throws IOException {
        return resourceService.getImgVerifyCode();
    }

    /**
     * 图片上传接口。
     *
     * <p>业务目的：接收 multipart 图片，落盘后返回可供 /image/** 直接访问的路径。类型
     * 白名单、UUID 重命名、写盘后 ImageIO 解码校验都在 Service，非法文件以业务异常拒绝。
     *
     * <p>边界：无鉴权；只接受单个 file 字段，返回相对路径而非完整 URL。
     *
     * @param file 上传文件
     * @return 图片访问路径
     */
    @Operation(summary = "图片上传接口")
    @PostMapping("/image")
    RestResp<String> uploadImage(
        @Parameter(description = "上传文件") @RequestParam("file") MultipartFile file) {
        return resourceService.uploadImage(file);
    }

}
