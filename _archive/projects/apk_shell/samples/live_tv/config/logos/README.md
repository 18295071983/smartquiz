# 频道台标目录

把频道台标 PNG/JPG 放在这里，然后在 `../channels.m3u` 的 `#EXTINF` 里写相对路径：

```m3u
#EXTINF:-1 tvg-logo="logos/cctv1.png" group-title="央视",CCTV-1 综合
```

- 相对路径按 `channels.m3u` 的地址解析；包内导出时壳会用 localhost 静态服务提供，直接可加载。
- 也可以写远程地址：`tvg-logo="https://example.com/logo.png"`。
- 没有提供台标、或加载失败时，原生首页/播放器会使用内置自适应台标：
  央视显示数字 + 电视图标，卫视显示首字 + 卫星图标，地方台显示首字 + 定位图标；
  新闻/体育/电影/少儿分组也有对应内置小图标。
- 建议 96×96 或 128×128 PNG，透明/圆角均可；加载时会自动采样并缓存。
