[OPEN] air-quality-sdk-leak

# Debug Session: air-quality-sdk-leak

## 症状
空气质量卡片显示 SDK 内部类名和 hashcode（如 `com.qweather.sdk.air.response.air.v1.HealthAdvice$5fc2ce@c739cf`）

## 假设
1. `Pollutant.getName()` / `Pollutant.getCode()` 返回的是对象而非 String
2. `PollutantConcentration.getValue()` / `getUnit()` 返回的是对象而非 String
3. 缓存中存有旧的破损数据
4. SDK 中某些 getter 返回复杂对象，需正确取值

## 调试计划
1. 查询 QWeather 官方文档确认 v1 API 返回字段类型
2. 反编译/检查 SDK jar 中的 Pollutant、AirIndex、PrimaryPollutant 等类定义
3. 添加日志确认实际返回类型
4. 修复并验证
