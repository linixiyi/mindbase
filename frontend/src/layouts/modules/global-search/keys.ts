/**
 * 全局搜索快捷键的修饰键标注：macOS / iOS 系平台显示 ⌘，其余平台显示 Ctrl。 顶栏触发钮键帽与命令面板空态键帽同源取值，避免两处各自硬编码漂移。 navigator.platform 已被逐步废弃，拼接
 * userAgent 兜底（个别浏览器可能返回空串）。
 */
const isApplePlatform = /mac|iphone|ipad|ipod/i.test(`${navigator.platform} ${navigator.userAgent}`);

export const modKeyLabel = isApplePlatform ? '⌘' : 'Ctrl';
