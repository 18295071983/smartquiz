$filter = "Generated|PERF|t/s|sendMessage|chatJson|complete|LlamaHelper|ModelBridge|AIChatActivity|推理|正在生成|生成出错"
adb -s 279b6c51 logcat -v time 2>&1 | Select-String $filter | Tee-Object -FilePath "D:\qzq\smartquiz\tools\monitor2.txt"
