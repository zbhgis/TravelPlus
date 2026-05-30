/**
 * 【全局配置】后端 API 地址与图片资源基础路径
 * 职责：集中管理环境相关常量，供 api.js / ui.js 等模块引用
 * - API_BASE：后端接口根地址（默认本地 8081）
 * - IMG_BASE：图片资源基础路径，默认与 API_BASE 同源
 * 注意：部署到不同环境时修改此文件即可，无需改动业务代码
 */
export const API_BASE = 'http://localhost:8081';
export const IMG_BASE = API_BASE;
