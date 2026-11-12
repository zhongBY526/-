#!/usr/bin/env bash
# W2 一键启动：后端 (Spring Boot) + 前端 (Vite)
# 用法：./start.sh                # 默认 8080 / 5173
#       BACKEND_PORT=18080 FRONT_PORT=15173 ./start.sh   # 自定义端口（改了后端端口需同步改 front/vite.config.js 的代理）
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
cd "$ROOT"

BACKEND_DIR="backed"
FRONT_DIR="front"
BACKEND_PORT="${BACKEND_PORT:-8080}"
FRONT_PORT="${FRONT_PORT:-5173}"
LOG_DIR="logs"
mkdir -p "$LOG_DIR"

fail() { echo "[启动失败] $*"; exit 1; }

# 1) 检查 .env
if [ ! -f "$BACKEND_DIR/.env" ]; then
  cp "$BACKEND_DIR/.env.example" "$BACKEND_DIR/.env"
  fail "已根据样例生成 $BACKEND_DIR/.env，请填入真实 DEEPSEEK_API_KEY 后重新运行。"
fi
if grep -qE '^DEEPSEEK_API_KEY=(sk-your-key)?[[:space:]]*$' "$BACKEND_DIR/.env"; then
  fail "$BACKEND_DIR/.env 里的 DEEPSEEK_API_KEY 仍是占位值，请填入真实 Key。"
fi

# 2) 检查端口
port_in_use() { curl -s --noproxy '*' -o /dev/null --max-time 2 "$1"; }
if port_in_use "http://127.0.0.1:${BACKEND_PORT}/"; then
  fail "后端端口 ${BACKEND_PORT} 已被占用。先关闭占用进程（netstat -ano | grep :${BACKEND_PORT}），或用 BACKEND_PORT=18080 换端口。"
fi
if port_in_use "http://127.0.0.1:${FRONT_PORT}/"; then
  fail "前端端口 ${FRONT_PORT} 已被占用。先关闭占用进程，或用 FRONT_PORT=15173 换端口。"
fi

# 3) 前端依赖
if [ ! -d "$FRONT_DIR/node_modules" ]; then
  echo "首次运行：安装前端依赖（npm install）..."
  ( cd "$FRONT_DIR" && npm install )
fi

# 4) 启动
echo "启动后端（日志 $LOG_DIR/backend.log）..."
( cd "$BACKEND_DIR" && ./mvnw spring-boot:run -Dspring-boot.run.arguments=--server.port="${BACKEND_PORT}" ) \
  > "$LOG_DIR/backend.log" 2>&1 &
BACK_PID=$!

echo "启动前端（日志 $LOG_DIR/front.log）..."
( cd "$FRONT_DIR" && npm run dev -- --port "${FRONT_PORT}" --strictPort ) \
  > "$LOG_DIR/front.log" 2>&1 &
FRONT_PID=$!

cleanup() {
  kill "$BACK_PID" "$FRONT_PID" 2>/dev/null || true
  wait "$BACK_PID" "$FRONT_PID" 2>/dev/null || true
}
trap cleanup INT TERM EXIT

# 5) 等后端就绪（最多 90 秒）
for _ in $(seq 1 90); do
  if port_in_use "http://127.0.0.1:${BACKEND_PORT}/"; then break; fi
  if ! kill -0 "$BACK_PID" 2>/dev/null; then
    fail "后端进程已退出，请查看 $LOG_DIR/backend.log"
  fi
  sleep 1
done
if ! port_in_use "http://127.0.0.1:${BACKEND_PORT}/"; then
  fail "后端 90 秒内未就绪，请查看 $LOG_DIR/backend.log"
fi

echo ""
echo "后端已就绪：http://localhost:${BACKEND_PORT}"
echo "前端地址：  http://localhost:${FRONT_PORT}（Vite 启动需 1-2 秒）"
echo "演示前预热：node warmup.js http://127.0.0.1:${BACKEND_PORT}"
echo "按 Ctrl+C 停止全部服务"
echo ""

wait
