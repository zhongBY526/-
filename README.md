#  流式对话 Lab

Vue3 + Spring Boot 3 + LangChain4j + DeepSeek，包含流式对话和公文要素抽取。

## 环境要求

- JDK 17
- Node.js 22+
- DeepSeek API Key

## 启动后端

```bash
cd backed
cp .env.example .env
# 编辑 .env，填入 DEEPSEEK_API_KEY
./mvnw spring-boot:run
