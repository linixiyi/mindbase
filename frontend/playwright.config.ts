import process from 'node:process';
import { defineConfig } from '@playwright/test';

/**
 * Playwright 冒烟配置（task-0011）。
 *
 * - 用例统一放 `./e2e`，根目录不再散落 spec。
 * - 本地默认连 dev server 9527；用 E2E_BASE_URL 可指向其它地址（如构建预览）。
 * - webServer 只在真跑用例时生效：`--list` 不会启动它；已有 dev server 时复用（reuseExistingServer）。
 * - 真跑浏览器是可选步骤，不纳入默认门禁；本卡只要求 `playwright test --list e2e` 绿。
 */
export default defineConfig({
  testDir: './e2e',
  timeout: 60_000,
  use: {
    // E2E_BASE_URL 未设时连本地 dev server 9527
    baseURL: process.env.E2E_BASE_URL || 'http://localhost:9527'
  },
  webServer: {
    command: 'pnpm dev',
    url: process.env.E2E_BASE_URL || 'http://localhost:9527',
    reuseExistingServer: true,
    timeout: 60_000
  }
});
