import { defineConfig } from 'vite'
import vue from '@vitejs/plugin-vue'
import { resolve } from 'path'

export default defineConfig({
  plugins: [vue()],
  resolve: {
    alias: {
      '@': resolve(__dirname, 'src')
    }
  },
  server: {
    host: '0.0.0.0',
    port: 6006,
    proxy: {
      '/api': {
        target: 'http://localhost:8080',
        changeOrigin: true,
        secure: false,
        timeout: 30 * 60 * 1000,
        proxyTimeout: 30 * 60 * 1000,
        configure: (proxy) => {
          proxy.on('error', (err, req, res) => {
            console.error(`[vite proxy] ${req.method} ${req.url} failed:`, err.message)
            if (!res.headersSent) {
              res.writeHead(502, { 'Content-Type': 'application/json;charset=utf-8' })
            }
            res.end(JSON.stringify({
              success: false,
              message: '前端代理到后端的连接中断，请重试上传',
              data: null,
              errorCode: 'PROXY_ERROR'
            }))
          })
        }
      }
    }
  }
})
