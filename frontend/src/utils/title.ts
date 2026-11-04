/** 标签页标题统一为「页面名 · MindBase」（pm-031）。 品牌名取 VITE_APP_TITLE，与 index.html 的 <title> 同源；页面名本身已含品牌名时不重复追加。 */
export function withAppBrandTitle(pageTitle: string) {
  const brand = import.meta.env.VITE_APP_TITLE;
  return brand && pageTitle !== brand ? `${pageTitle} · ${brand}` : pageTitle;
}
