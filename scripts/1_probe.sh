#!/system/bin/sh
# 【步骤1】列出所有音频输出设备和产品策略（用于确认设备都在线）
DIR=$(cd "$(dirname "$0")" && pwd)
DEX="$DIR/MultiAudio.dex"
CMD="app_process -Djava.class.path=$DEX /system/bin --nice-name=multiaudio MultiAudio list"
if [ "$(id -u)" = "0" ]; then exec $CMD; else exec su -c "$CMD"; fi
