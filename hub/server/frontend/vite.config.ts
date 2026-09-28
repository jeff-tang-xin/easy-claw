import {defineConfig} from 'vite';
import react from '@vitejs/plugin-react';

// 构建产物输出到 hub/server 源码资源目录 src/main/resources/static，
// 与主前端一致：由 Spring Boot 单端口托管（hub 端口 18081，dev 代理 /api 到 18081）
export default defineConfig({
  plugins: [react()],
  build: {
    outDir: '../src/main/resources/static',
    emptyOutDir: true,
    sourcemap: true,
  },
  server: {
    port: 5174,
    proxy: {
      '/api': 'http://localhost:18081',
    },
  },
});
