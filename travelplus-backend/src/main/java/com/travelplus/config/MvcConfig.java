package com.travelplus.config;

import com.travelplus.utils.LoginInterceptor;
import com.travelplus.utils.RefreshTokenInterceptor;
import com.travelplus.utils.SystemConstants;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.web.servlet.config.annotation.CorsRegistry;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.ResourceHandlerRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

import java.io.File;

import jakarta.annotation.Resource;

/**
 * 【跨域与拦截器配置的实现】Spring MVC 全局配置：CORS 跨域、静态资源映射与拦截器注册。
 * 1) addCorsMappings —— 允许任意来源跨域（allowedOriginPatterns("*")，不启用 credentials），放行 GET/POST/PUT/DELETE/OPTIONS 与任意请求头，预检结果缓存 3600 秒；移动端前端独立端口（如 5500）可直接调用。
 * 2) addResourceHandlers —— 将 /imgs/** 映射到 SystemConstants.IMAGE_UPLOAD_DIR（启动时自动 mkdirs），无 nginx 时也可直接通过后端访问上传图片。
 * 3) addInterceptors —— 注册两个拦截器：order(0) RefreshTokenInterceptor（读 authorization → Redis 续期 → 写 UserHolder，对所有路径生效），order(1) LoginInterceptor（校验登录态，exclude 放行景点/门票/类型/上传/验证码/登录/注册/用户主页/静态图片/error 等匿名路径）。
 */
@Configuration
public class MvcConfig implements WebMvcConfigurer {

    @Resource
    private StringRedisTemplate stringRedisTemplate;

    @Override
    public void addCorsMappings(CorsRegistry registry) {
        // 移动端前端运行在独立端口（如 5500），允许任意来源跨域访问全部接口
        registry.addMapping("/**")
                .allowedOriginPatterns("*")
                .allowedMethods("GET", "POST", "PUT", "DELETE", "OPTIONS")
                .allowedHeaders("*")
                .maxAge(3600);
    }

    @Override
    public void addResourceHandlers(ResourceHandlerRegistry registry) {
        // 确保上传目录存在，无 nginx 时也可直接通过 /imgs/** 访问图片
        File dir = new File(SystemConstants.IMAGE_UPLOAD_DIR);
        if (!dir.exists()) {
            dir.mkdirs();
        }
        registry.addResourceHandler("/imgs/**")
                .addResourceLocations("file:" + SystemConstants.IMAGE_UPLOAD_DIR);
    }

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        // 登录拦截器
        registry.addInterceptor(new LoginInterceptor())
                .excludePathPatterns(
                        // 【景点点赞的实现】/attraction/** 由整段放行细化为三条匿名路径：
                        // /attraction（POST/PUT 新增/更新）、/attraction/*（GET 详情）、/attraction/of/**（类型/名称分页）——
                        // 使 PUT /attraction/like/{id} 落在拦截范围内（点赞需登录，未登录 401），其余景点查询保持匿名
                        "/attraction",
                        "/attraction/*",
                        "/attraction/of/**",
                        "/ticket/**",
                        "/attraction-type/**",
                        "/upload/**",
                        "/imgs/**",
                        "/note/hot",
                        "/map/points", // 【地图数据的实现】地图点位公开（无需登录，地图先于登录页加载）
                        "/user/code",
                        "/user/login",
                        "/user/register", // 注册接口匿名可访问（密码/验证码登录接口本就在放行列表）
                        "/user/home/**", // 用户主页公开（未登录可浏览，service 内对 UserHolder 判空，followed 恒为 false）
                        "/search/**", // 【全局搜索的实现】全局搜索公开（搜索结果页/热门词条/输入联想均无需登录）
                        "/error"
                ).order(1);
        // token刷新的拦截器
        registry.addInterceptor(new RefreshTokenInterceptor(stringRedisTemplate)).addPathPatterns("/**").order(0);
    }
}
