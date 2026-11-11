import process from 'node:process';
import { expect, test } from '@playwright/test';

/**
 * 知识库列可见性冒烟（原 frontend/playwright-kb-column.spec.ts，task-0011 迁入 e2e/）。
 *
 * - 地址不再硬编码旧端口与主机：相对路径由 playwright.config.ts 的 baseURL 解析， 即读 E2E_BASE_URL（默认 http://localhost:9527）；页面为 hash 路由，故用
 *   `/#/xxx`。
 * - 登录 token 改读 E2E_TOKEN；缺 token 时跳过（该用例需要真实登录态）。
 */
const token = process.env.E2E_TOKEN || '';

test('knowledge base column visibility', async ({ page }) => {
  test.skip(!token, 'E2E_TOKEN is not set; knowledge-base smoke needs a real login token');

  await page.goto('/#/login', { waitUntil: 'domcontentloaded' });
  await page.evaluate(value => {
    localStorage.setItem('token', value);
  }, token);

  await page.goto('/#/knowledge-base', { waitUntil: 'networkidle' });
  await expect(page.getByText('文件列表')).toBeVisible();
  await expect(page.getByText('向量化消耗')).toBeVisible();
});
