# 和风天气 Android SDK 使用指南

## 前置准备

1. 注册账号 → https://console.qweather.com
2. 创建项目 → 获取 `项目ID`、`凭据ID`、`私钥`
3. 生成 Ed25519 密钥对：
   ```bash
   openssl genpkey -algorithm ED25519 -out ed25519-private.pem
   openssl pkey -pubout -in ed25519-private.pem > ed25519-public.pem
   ```
4. 上传公钥到控制台 → 获取 `kid`（凭据ID）
5. 获取 API Host → 控制台-设置中查看，格式如 `abc1234xyz.def.qweatherapi.com`

## 身份认证（JWT）

SDK 5+ 仅支持 JWT（JSON Web Token）身份认证，使用 Ed25519 算法签名。

### 第1步：生成 Ed25519 密钥对

**方式一：终端（推荐）**

```bash
openssl genpkey -algorithm ED25519 -out ed25519-private.pem
openssl pkey -pubout -in ed25519-private.pem > ed25519-public.pem
```

生成两个文件：
- `ed25519-private.pem` — 私钥，用于签名，妥善保管
- `ed25519-public.pem` — 公钥，需要上传到和风天气控制台

**方式二：浏览器（Chrome 137+/Edge 137+/Firefox 129+/Safari 17+）**

打开浏览器控制台（F12），粘贴执行：

```javascript
async function generateEd25519Pem() {
  const k = await crypto.subtle.generateKey({name:"Ed25519"},true,["sign","verify"]);
  const p8 = await crypto.subtle.exportKey("pkcs8",k.privateKey);
  const spki = await crypto.subtle.exportKey("spki",k.publicKey);
  const pem = (d,t)=>{
    let b=btoa(String.fromCharCode(...new Uint8Array(d)));
    return`-----BEGIN ${t}-----\n${b.match(/.{1,64}/g).join("\n")}\n-----END ${t}-----`;
  };
  const priv=pem(p8,"PRIVATE KEY");
  const pub=pem(spki,"PUBLIC KEY");
  console.log("PrivateKey:\n",priv,"\n\nPublicKey:\n",pub);
  return{priv,pub};
}
generateEd25519Pem();
```

### 第2步：上传公钥到控制台

1. 登录 https://console.qweather.com/project
2. 点击项目 → 凭据区域 → "添加凭据"
3. 输入凭据名称
4. 身份认证方式选择 **JSON Web Token**
5. 粘贴公钥内容（完整的 PEM 字符串，包含首尾标记）
6. 保存 → 获得 `凭据ID (kid)` 和 `项目ID (sub)`

### 第3步：获取 API Host

1. 登录控制台 → 设置
2. 查看你的 API Host，格式如 `abc1234xyz.def.qweatherapi.com`

### JWT 结构说明

一个完整的 JWT 由三部分组成：`header.payload.signature`

**Header（头部）**
```json
{
    "alg": "EdDSA",      // 签名算法，固定为 EdDSA
    "kid": "YOUR_KID"    // 凭据ID
}
```

**Payload（载荷）**
```json
{
    "sub": "YOUR_PROJECT_ID",  // 项目ID（签发主体）
    "iat": 1703912400,         // 签发时间（UNIX时间戳，建议当前时间-30秒）
    "exp": 1703912940          // 过期时间（最长24小时/86400秒）
}
```

> **注意**：Header 和 Payload 中仅添加上述指定参数，不要添加其他敏感信息。

### 在线调试工具

- **JWT 调试器**：https://jwt.qweather.com — 在线生成测试 Token
- **JWT 验证器**：控制台 → JWT 验证 — 检查 Token 是否有效

---

## 初始化

```java
// 1. 初始化实例（YOUR_HOST 替换为你的 API Host）
QWeather.getInstance(context, "your-api-host.qweatherapi.com")
        .setLogEnable(true);  // 生产环境设为 false

// 2. 设置 JWT Token 生成器
JWTGenerator jwt = new JWTGenerator(
    "YOUR_PRIVATE_KEY",   // 私钥内容
    "YOUR_PROJECT_ID",    // 项目ID
    "YOUR_KID"            // 凭据ID
);
QWeather.instance.setTokenGenerator(jwt);
```

## 常用 API 示例

### 实时天气

```java
WeatherParameter parameter = new WeatherParameter("101010100")  // 北京 LocationID
        .lang(Lang.ZH_HANS)
        .unit(Unit.METRIC);

QWeather.instance.weatherNow(parameter, new Callback<WeatherNowResponse>() {
    @Override
    public void onSuccess(WeatherNowResponse response) {
        // response.now.temp      → 温度
        // response.now.text      → 天气描述（如"多云"）
        // response.now.humidity  → 湿度
        // response.now.windDir   → 风向
        // response.now.windScale → 风力等级
    }

    @Override
    public void onFailure(ErrorResponse errorResponse) {
        // errorResponse.code → 错误码
    }

    @Override
    public void onException(Throwable e) {
        e.printStackTrace();
    }
});
```

### 每日天气预报（3天/7天/15天/30天）

```java
WeatherParameter parameter = new WeatherParameter("101010100")
        .lang(Lang.ZH_HANS)
        .unit(Unit.METRIC);

QWeather.instance.weather3d(parameter, new Callback<WeatherDailyResponse>() {
    @Override
    public void onSuccess(WeatherDailyResponse response) {
        for (Daily daily : response.daily) {
            // daily.fxDate    → 预报日期
            // daily.tempMax   → 最高温度
            // daily.tempMin   → 最低温度
            // daily.textDay   → 白天天气
            // daily.textNight → 夜间天气
            // daily.sunrise   → 日出时间
            // daily.sunset    → 日落时间
        }
    }

    @Override
    public void onFailure(ErrorResponse errorResponse) {}

    @Override
    public void onException(Throwable e) {}
});
```

### 逐小时天气预报

```java
QWeather.instance.weather24h(parameter, new Callback<WeatherHourlyResponse>() {
    @Override
    public void onSuccess(WeatherHourlyResponse response) {
        for (Hourly hourly : response.hourly) {
            // hourly.fxTime  → 时间
            // hourly.temp    → 温度
            // hourly.text    → 天气描述
        }
    }
    // ...
});
```

### 空气质量（v1 API）

> ⚠️ **v7/air/now 已于 2026-06-01 停止服务**，请使用 v1 API。

```java
// v1 API 使用经纬度参数，不再支持 LocationID
AirV1Parameter parameter = new AirV1Parameter(39.92, 116.41);  // 纬度, 经度
parameter.setLang(Lang.ZH_HANS);

QWeather.instance.airCurrent(parameter, new Callback<AirV1CurrentResponse>() {
    @Override
    public void onSuccess(AirV1CurrentResponse response) {
        // response.getIndexes() → AQI指数列表
        //   index.getAqiDisplay() → AQI显示值
        //   index.getLevel()      → 等级
        //   index.getCategory()  → 类别
        // response.getPollutants() → 污染物浓度列表
        //   pollutant.getName()   → 污染物名称（PM2.5, PM10, NO2等）
        //   pollutant.getConcentration().getValue() → 浓度值
        //   pollutant.getConcentration().getUnit() → 单位
    }

    @Override
    public void onFailure(ErrorResponse errorResponse) {}

    @Override
    public void onException(Throwable e) {}
});
```

### 天气预警（v1 API）

> ⚠️ **v7/warning/now 将于 2026-09-01 停止服务**，请迁移至 v1 API。

```java
// v1 API 使用经纬度参数，不再支持 LocationID
WeatherAlertCurrentParameter parameter = new WeatherAlertCurrentParameter(39.92, 116.41, true);  // 纬度, 经度, 是否返回本地时间
parameter.setLang(Lang.ZH_HANS);

QWeather.instance.weatherAlertCurrent(parameter, new Callback<WeatherAlertCurrentResponse>() {
    @Override
    public void onSuccess(WeatherAlertCurrentResponse response) {
        for (WeatherAlert alert : response.getAlerts()) {
            // alert.getSeverity()         → 严重程度（extreme/severe/moderate/minor）
            // alert.getHeadline()         → 标题
            // alert.getDescription()      → 详细描述
            // alert.getInstruction()      → 防御指南
            // alert.getEventType().getName() → 预警类型名称
            // alert.getColor().getCode()  → 颜色代码（red/orange/yellow/blue）
            // alert.getIssuedTime()       → 发布时间
            // alert.getEffectiveTime()    → 生效时间
            // alert.getExpireTime()       → 失效时间
        }
    }

    @Override
    public void onFailure(ErrorResponse errorResponse) {}

    @Override
    public void onException(Throwable e) {}
});
```

## v1 API 迁移对照表

| 功能 | v7 方法（已弃用） | v1 方法（当前） | 状态 |
|------|-------------------|----------------|------|
| 空气质量 | `airNow(AirParameter)` | `airCurrent(AirV1Parameter)` | ⚠️ v7 已于 2026-06-01 停止服务 |
| 天气预警 | `warningNow(WarningNowParameter)` | `weatherAlertCurrent(WeatherAlertCurrentParameter)` | ⚠️ v7 将于 2026-09-01 停止服务 |

### 参数格式变化

| 项目 | v7 | v1 |
|------|----|----|
| 空气质量参数类 | `AirParameter(locationId)` | `AirV1Parameter(lat, lon)` |
| 天气预警参数类 | `WarningNowParameter(locationId)` | `WeatherAlertCurrentParameter(lat, lon, localTime)` |
| 参数类型 | LocationID 或 "经度,纬度" | 仅支持经纬度（纬度在前） |
| 响应类 | `AirNowResponse` | `AirV1CurrentResponse` |
| 响应类 | `WarningResponse` | `WeatherAlertCurrentResponse` |

### 主机要求

- **v1 API 必须使用 JWT 专用主机**（如 `abc1234xyz.def.qweatherapi.com`）
- 公共主机 `api.qweather.com` 不接受 JWT Bearer Token，会返回 403 Forbidden

## LocationID 示例

| 城市 | LocationID |
|------|------------|
| 北京 | 101010100 |
| 上海 | 101020100 |
| 广州 | 101280101 |
| 深圳 | 101280601 |
| 成都 | 101270101 |
| 杭州 | 101210101 |
| 武汉 | 101200101 |
| 南京 | 101190101 |
| 重庆 | 101040100 |
| 西安 | 101110101 |

也支持经纬度坐标：`location=116.41,39.92`

## 可用 API 列表

| API | 方法 | 说明 |
|-----|------|------|
| 实时天气 | `weatherNow()` | 当前天气实况 |
| 3日预报 | `weather3d()` | 未来3天每日预报 |
| 7日预报 | `weather7d()` | 未来7天每日预报 |
| 15日预报 | `weather15d()` | 未来15天每日预报 |
| 30日预报 | `weather30d()` | 未来30天每日预报 |
| 24小时预报 | `weather24h()` | 逐小时预报 |
| 72小时预报 | `weather72h()` | 逐小时预报 |
| 168小时预报 | `weather168h()` | 逐小时预报 |
| 5分钟降水 | `minutely5m()` | 分钟级降水预报 |
| 天气预警 | `weatherAlertCurrent()` | 当前生效预警（v1） |
| 天气指数 | `indices1d()` | 生运、紫外线等指数 |
| 空气质量实况 | `airCurrent()` | 当前AQI（v1） |
| 空气质量预报 | `airDaily()` | 未来空气质量预报 |

## 实时天气返回字段

| 字段 | 说明 |
|------|------|
| temp | 温度（摄氏度） |
| feelsLike | 体感温度 |
| icon | 天气图标代码 |
| text | 天气描述（多云、晴等） |
| wind360 | 风向360角度 |
| windDir | 风向（东南风等） |
| windScale | 风力等级 |
| windSpeed | 风速（km/h） |
| humidity | 相对湿度（%） |
| precip | 降水量（mm） |
| pressure | 气压（hPa） |
| vis | 能见度（km） |
| cloud | 云量（%） |
| dew | 露点温度 |

## 每日预报返回字段

| 字段 | 说明 |
|------|------|
| fxDate | 预报日期 |
| tempMax | 最高温度 |
| tempMin | 最低温度 |
| iconDay / textDay | 白天天气图标/描述 |
| iconNight / textNight | 夜间天气图标/描述 |
| windDirDay / windScaleDay | 白天风向/风力 |
| sunrise / sunset | 日出/日落时间 |
| humidity | 湿度 |
| precip | 降水量 |
| uvIndex | 紫外线指数 |

## v1 空气质量响应字段

**AirV1CurrentResponse**

| 方法 | 返回类型 | 说明 |
|------|---------|------|
| `getIndexes()` | `List<AirIndex>` | AQI 指数列表 |
| `getPollutants()` | `List<Pollutant>` | 污染物浓度列表 |
| `getStations()` | `List<Station>` | 监测站信息 |

**AirIndex（AQI 指数）**

| 方法 | 返回类型 | 说明 |
|------|---------|------|
| `getAqiDisplay()` | String | AQI 显示值 |
| `getLevel()` | String | 等级（优/良/轻度/中度/重度/严重） |
| `getCategory()` | String | 类别描述 |
| `getCode()` | String | 指数代码 |
| `getName()` | String | 指数名称 |
| `getColor()` | AirColor | 颜色信息 |
| `getPrimaryPollutant()` | PrimaryPollutant | 首要污染物 |
| `getHealth()` | Health | 健康建议 |

**Pollutant（污染物）**

| 方法 | 返回类型 | 说明 |
|------|---------|------|
| `getName()` | String | 污染物名称（PM2.5, PM10, NO2, SO2, CO, O3） |
| `getFullName()` | String | 全称 |
| `getConcentration()` | PollutantConcentration | 浓度值和单位 |

## v1 天气预警响应字段

**WeatherAlertCurrentResponse**

| 方法 | 返回类型 | 说明 |
|------|---------|------|
| `getAlerts()` | `List<WeatherAlert>` | 预警列表 |
| `getMetadata()` | WeatherAlertMetadata | 元数据 |

**WeatherAlert（预警条目）**

| 方法 | 返回类型 | 说明 |
|------|---------|------|
| `getId()` | String | 预警ID |
| `getHeadline()` | String | 标题 |
| `getDescription()` | String | 详细描述 |
| `getInstruction()` | String | 防御指南 |
| `getSeverity()` | String | 严重程度（extreme/severe/moderate/minor） |
| `getUrgency()` | String | 紧急程度 |
| `getCertainty()` | String | 确定性 |
| `getSenderName()` | String | 发布机构 |
| `getIssuedTime()` | String | 发布时间 |
| `getEffectiveTime()` | String | 生效时间 |
| `getExpireTime()` | String | 失效时间 |
| `getOnsetTime()` | String | 起始时间 |
| `getEventType()` | WeatherAlertEventType | 预警类型 |
| `getColor()` | WeatherAlertColor | 预警颜色 |
| `getIcon()` | String | 预警图标 |

## 错误码

| 错误码 | 说明 |
|--------|------|
| 200 | 请求成功 |
| 204 | 请求成功，但你查询的地区暂时没有你需要的数据 |
| 400 | 请求错误，可能包含错误的请求参数或缺少必选的请求参数 |
| 401 | 身份认证失败，可能使用了错误的KEY、数字签名错误、KEY的类型错误 |
| 402 | 超过访问次数或余额不足以支持继续访问服务 |
| 403 | 无访问权限，可能是绑定的包名、IP地址不正确 |
| 404 | 查询的数据或地区不存在 |
| 429 | 超过限定的QPM（每分钟访问次数） |
| 500 | 无响应或超时，接口服务异常 |

## 项目和凭据管理

- 控制台：https://console.qweather.com/project
- 创建项目 → 获取项目ID
- 添加凭据 → 选择 JWT → 获取凭据ID(kid) + 私钥
- 获取 API Host → 控制台设置页面

## 认证兼容性

| 认证方式 | API v7 | API v1 | SDK 5+ |
|----------|--------|--------|--------|
| JWT | ✅ | ✅ | ✅ |
| API KEY | ✅ | ❌ | ❌ 不支持 |

> SDK 5+ 仅支持 JWT，不支持 API KEY。

## 官方文档链接

- SDK 配置：https://dev.qweather.com/docs/configuration/android-sdk-config/
- 身份认证：https://dev.qweather.com/docs/configuration/authentication/
- API Host：https://dev.qweather.com/docs/configuration/api-host/
- 项目和凭据：https://dev.qweather.com/docs/configuration/project-and-key/
- 天气 API：https://dev.qweather.com/docs/api/weather/
- 分钟预报：https://dev.qweather.com/docs/api/minutely/
- 天气预警：https://dev.qweather.com/docs/api/warning/
- 天气指数：https://dev.qweather.com/docs/api/indices/
- 空气质量：https://dev.qweather.com/docs/api/air-quality/
- 错误码：https://dev.qweather.com/docs/resource/error-code/
- 城市列表：https://dev.qweather.com/docs/resource/location-list/
- 多语言：https://dev.qweather.com/docs/resource/language/
- 天气图标：https://icons.qweather.com/
