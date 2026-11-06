package io.github.xxyopen.novel.resource.interceptor;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;

import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

/**
 * 文件（图片）访问拦截器。
 *
 * <p>职责：把 /image/** 的静态图片请求直接以字节流写回响应。WebConfig 把本拦截器注册在
 * 图片目录前缀上，这些 URL 并没有对应的 Controller 方法——图片读取「绕过 Controller」，
 * 由 preHandle 直接完成响应后返回 false 终止请求，省掉一次 handler 查找与消息转换开销。
 *
 * <p>安全边界（重点）：早期实现是 new File(fileUploadPath + requestUri) 直接拼接 URI，
 * 形如 /image/../../etc/passwd 的请求能借 .. 跳出上传目录读到任意文件，属于目录穿越漏洞。
 * 这里改为把 URI 当相对路径拼到上传根目录下，先 normalize 消除 ../，再用 startsWith
 * 判断目标是否仍落在根目录内，并用 toRealPath 兜住软链绕过；越界或文件不存在一律 404，
 * 绝不把流写出去。
 *
 * <p>缓存取舍：响应设置 10 天 expires 头。图片以 UUID 命名、内容不可变，长缓存既安全
 * 又能大幅减少重复请求；换一张新图会得到新 URL，不存在「缓存住旧内容」的问题。
 *
 * @author xiongxiaoyang
 * @date 2022/5/22
 */
@Component
@RequiredArgsConstructor
public class FileInterceptor implements HandlerInterceptor {

    @Value("${novel.file.upload.path}")
    private String fileUploadPath;

    /**
     * 拦截图片读取请求并直出文件流。
     *
     * <p>关键步骤：① 取上传根目录真实路径作为边界；② URI 去掉前导斜杠后拼到根目录下并
     * normalize，用 startsWith + toRealPath 双重校验未越界、且是真实存在的普通文件；
     * ③ 设置 10 天缓存头；④ 把文件字节写入响应并返回 false，不再进入后续 Controller。
     *
     * <p>边界：路径越界、软链指向目录外、文件不存在，统一 sendError(404) 并返回 false，
     * 既不泄露目录结构，也不会因打开输出流后再报错而产生半成品响应。
     */
    @SuppressWarnings("NullableProblems")
    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response,
        Object handler) throws Exception {
        // 获取请求的 URI（形如 /image/2026/11/05/uuid.jpg）
        String requestUri = request.getRequestURI();

        // 上传根目录的真实路径（解析软链），作为允许访问的边界
        Path baseDir = Paths.get(fileUploadPath).toRealPath();
        // URI 以 / 开头，去掉前导斜杠后按「相对根目录」拼接，避免被当成绝对路径覆盖根目录；
        // normalize 消掉 ../ 等片段，使 /image/../../etc/passwd 现出真实目标
        String relativeUri = requestUri.startsWith("/") ? requestUri.substring(1) : requestUri;
        Path target = Paths.get(fileUploadPath, relativeUri).normalize();
        // 越界（不在根目录内）、不是普通文件（不存在/是目录）、或软链真实目标越界，一律 404。
        // isRegularFile 短路在前，保证文件不存在时不会调用 toRealPath 抛异常
        if (!target.startsWith(baseDir) || !Files.isRegularFile(target)
            || !target.toRealPath().startsWith(baseDir)) {
            response.sendError(HttpServletResponse.SC_NOT_FOUND);
            return false;
        }

        // 图片是静态资源，浏览器/CDN 可缓存 10 天；文件名为 UUID、内容不会变，长缓存安全
        response.setDateHeader("expires", System.currentTimeMillis() + 60 * 60 * 24 * 10 * 1000);
        // 直接把文件字节写回响应；本拦截器注册在 /image/** 上，这些 URL 没有对应 Controller
        try (OutputStream out = response.getOutputStream();
             InputStream input = Files.newInputStream(target)) {
            byte[] b = new byte[4096];
            for (int n; (n = input.read(b)) != -1; ) {
                out.write(b, 0, n);
            }
        }
        // 响应已由拦截器写完，返回 false 终止 DispatcherServlet 后续处理
        return false;
    }

}
