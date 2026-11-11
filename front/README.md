# front

W2 流式对话 Lab 的前端（Vue 3 + Vite）。完整启动步骤、接口协议与演示脚本见仓库根目录的 [README](../README.md)。

```sh
npm install
npm run dev     # http://localhost:5173，/api 代理到 http://localhost:8080
npm run build   # 产物在 dist/
```

- `src/views/ChatViews.vue`：SSE 流式对话，展示首字耗时、结束原因，支持中断与新会话
- `src/views/ExtractView.vue`：公文要素抽取
- `vite.config.js`：开发端口与 `/api` 代理配置
