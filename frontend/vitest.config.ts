import { defineConfig } from 'vitest/config';

export default defineConfig({
  test: {
    // DOMPurify 依赖 window（任务书失败处理预案：Node 环境报 window is not defined 时用 jsdom）
    environment: 'jsdom',
    include: ['src/**/*.spec.ts']
  }
});
