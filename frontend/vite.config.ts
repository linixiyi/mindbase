import process from 'node:process';
import { URL, fileURLToPath } from 'node:url';
import { defineConfig, loadEnv } from 'vite';
import { setupVitePlugins } from './build/plugins';
import { createViteProxy, getBuildTime } from './build/config';

export default defineConfig(configEnv => {
  const viteEnv = loadEnv(configEnv.mode, process.cwd()) as unknown as Env.ImportMeta;

  const buildTime = getBuildTime();

  const enableProxy = configEnv.command === 'serve' && !configEnv.isPreview;

  return {
    base: viteEnv.VITE_BASE_URL,
    resolve: {
      alias: {
        '~': fileURLToPath(new URL('./', import.meta.url)),
        '@': fileURLToPath(new URL('./src', import.meta.url))
      }
    },
    css: {
      preprocessorOptions: {
        scss: {
          api: 'modern-compiler',
          additionalData: `@use "@/styles/scss/global.scss" as *;`
        }
      }
    },
    plugins: setupVitePlugins(viteEnv, buildTime),
    define: {
      BUILD_TIME: JSON.stringify(buildTime)
    },
    server: {
      host: '0.0.0.0',
      port: 9527,
      open: process.env.VITE_OPEN !== 'false',
      proxy: createViteProxy(viteEnv, enableProxy),
      allowedHosts: ['u45964x883.zicp.vip']
    },
    preview: {
      port: 9725
    },
    build: {
      reportCompressedSize: false,
      sourcemap: viteEnv.VITE_SOURCE_MAP === 'Y',
      commonjsOptions: {
        ignoreTryCatch: false
      },
      rollupOptions: {
        output: {
          // task-0013：把首屏静态引入的大依赖从入口 chunk 拆出为独立并行 chunk。
          // 1) markdown 渲染链（vue-markdown-shiki → shiki/markdown-it/@mdit-vue/*，以及
          //    dompurify 与 markdown-it 插件族如 @traptitech/katex、link-attributes）。
          //    注意 shiki 只匹配运行时：@shikijs/langs、@shikijs/themes 等语法/主题数据包，
          //    以及 shiki/dist/langs、shiki/dist/themes 门面文件必须保持按需动态加载
          //    （把语法数据并入会把本 chunk 撑到 9MB+ 静态体积，等于取消语言懒加载）。
          // 2) naive-ui 单独分包：UI 库是渲染链之外入口 chunk 的最大静态依赖。
          // 背景：main.ts 静态引入 markdown 渲染链，入口 chunk 曾达 1.5MB（未压缩）；
          // echarts 依赖路由懒加载自然分包，保持现状——不在此处匹配 echarts，避免改变其分包行为。
          // 若拆包引入循环依赖或首屏报错，回退对应匹配项并在 scripts/frontend_bundle_check.sh 注释记录，
          // 不得以提高体积阈值的方式掩盖（见该脚本头部说明）。
          manualChunks(id) {
            if (!id.includes('node_modules')) {
              return undefined;
            }
            const isMarkdownChain =
              /[\\/]node_modules[\\/](markdown-it|@mdit-vue|@traptitech|dompurify)[\\/]/.test(id) ||
              /[\\/]node_modules[\\/]@shikijs[\\/]core[\\/]/.test(id) ||
              /[\\/]node_modules[\\/]shiki[\\/]dist[\\/][^\\/]+\.mjs$/.test(id) ||
              // vue-markdown-shiki 只匹配静态入口 dist/index.mjs（markdown-it/shiki 核心已预打包其中）；
              // 其 dist 下其余约 280 个文件是按语言动态 import 的语法数据（0 静态相对导入、281 个动态 import），
              // 整包并入会把这些数据也变成静态 chunk（实测 9.3MB），等于取消语言懒加载
              /[\\/]node_modules[\\/]vue-markdown-shiki[\\/]dist[\\/]index\.mjs$/.test(id);
            if (isMarkdownChain) {
              return 'markdown';
            }
            if (/[\\/]node_modules[\\/]naive-ui[\\/]/.test(id)) {
              return 'naive-ui';
            }
            return undefined;
          }
        }
      }
    }
  };
});
