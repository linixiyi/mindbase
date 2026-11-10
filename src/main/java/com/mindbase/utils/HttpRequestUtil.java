package com.mindbase.utils;

import com.mindbase.security.ClientIpResolver;
import jakarta.servlet.http.HttpServletRequest;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;

/**
 * 请求工具类
 *
 * @author YiHui
 * @date 2026/03/18
 */
@Slf4j
public class HttpRequestUtil {

    /**
     * 与 Spring 装配的 {@link ClientIpResolver} 桥接（由其 @PostConstruct 注册）。
     * 未注册时（如脱离 Spring 的单测环境）保守回落 remoteAddr，不采信任何转发头。
     */
    private static volatile ClientIpResolver clientIpResolver;

    /**
     * readData
     *
     * @param request request
     * @return result
     */
    // CHECKSTYLE:OFF:InnerAssignment
    public static String readReqData(HttpServletRequest request) {
        BufferedReader reader = null;
        try {
            reader = new BufferedReader(new InputStreamReader(request.getInputStream(), StandardCharsets.UTF_8));
            StringBuilder stringBuilder = new StringBuilder();
            String line;
            while ((line = reader.readLine()) != null) {
                stringBuilder.append(line);
            }
            return stringBuilder.toString();
        } catch (Exception e) {
            throw new RuntimeException(e);
        } finally {
            if (reader != null) {
                try {
                    reader.close();
                } catch (IOException e) {
                    log.error("请求参数解析异常! {}", request.getRequestURI(), e);
                }
            }
        }
    }


    public static String getClientIp() {
        // 从上下文中获取 HttpServletRequest
        HttpServletRequest request = ((ServletRequestAttributes) RequestContextHolder.currentRequestAttributes()).getRequest();
        if (request != null) {
            return resolveClientIp(request);
        }
        return "";
    }

    /**
     * 客户端 IP 信任策略解析，委托 {@link ClientIpResolver} 同一实现（task-0019）：
     * 仅当直连来源命中 security.trusted-proxies 时才采信转发头，
     * 解析失败一律回落 remoteAddr，不返回攻击者可控值。
     */
    public static String resolveClientIp(HttpServletRequest request) {
        if (request == null) {
            return "unknown";
        }
        ClientIpResolver resolver = clientIpResolver;
        if (resolver != null) {
            return resolver.resolveClientIp(request);
        }
        String remoteAddr = request.getRemoteAddr();
        return isUsableIp(remoteAddr) ? remoteAddr : "unknown";
    }

    public static void setClientIpResolver(ClientIpResolver resolver) {
        clientIpResolver = resolver;
    }

    private static boolean isUsableIp(String ip) {
        return ip != null && !ip.isBlank() && !"unknown".equalsIgnoreCase(ip);
    }
}