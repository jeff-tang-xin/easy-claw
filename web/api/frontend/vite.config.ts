import {defineConfig} from 'vite';
import react from '@vitejs/plugin-react';

// 构建产物输出到 Spring Boot 源码资源目录 src/main/resources/static，
// mvn compile/package 时 frontend-maven-plugin 先执行构建，resources 阶段自动复制进 target/classes（单端口托管）
export default defineConfig({
  plugins: [react()],
  build: {
    outDir: '../src/main/resources/static',
    emptyOutDir: true,
    sourcemap: true,
  },
  server: {
    port: 5173,
    proxy: {
      '/api': 'http://localhost:8080',
      '/ws': {
        target: 'http://localhost:8080',
        ws: true,
        changeOrigin: true,
      },
    },
  },
});
