#!/system/bin/sh
# 每秒更新进度，持续 60 秒
touch /data/local/tmp/agent_progress.enabled
for i in $(seq 1 60); do
    echo "测试进度 $i/60" > /data/local/tmp/agent_progress.txt
    sleep 1
done
rm -f /data/local/tmp/agent_progress.txt
echo "测试完成"