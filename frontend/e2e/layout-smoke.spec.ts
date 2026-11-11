import { expect, test } from '@playwright/test';

/**
 * 布局冒烟（task-0011）：两条用例都不依赖登录态与后端。 页面为 hash 路由（.env VITE_ROUTER_HISTORY_MODE=hash），地址统一写成 `/#/xxx`， 相对路径由
 * playwright.config.ts 的 baseURL（E2E_BASE_URL，默认 9527）解析。 未登录重定向是纯前端路由守卫行为（src/router/guard/route.ts），不需要后端在线。
 */

test('登录页标题与输入框可见', async ({ page }) => {
  await page.goto('/#/login');

  await expect(page.getByRole('heading', { level: 1 })).toContainText('MindBase');
  await expect(page.getByPlaceholder('请输入用户名')).toBeVisible();
  await expect(page.getByPlaceholder('请输入密码')).toBeVisible();
});

test('未登录访问 /#/chat 被重定向到登录页', async ({ page }) => {
  await page.goto('/#/chat');

  await expect(page).toHaveURL(/#\/login/);
  await expect(page.getByPlaceholder('请输入用户名')).toBeVisible();
});
