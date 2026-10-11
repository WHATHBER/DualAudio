#!/system/bin/sh
# 【诊断】列出系统里所有与音量相关的 API（方法签名）
# 用于确认这台机器上到底有哪些"按设备设置音量"的接口可用。
DIR=$(cd "$(dirname "$0")" && pwd)
DEX="$DIR/MultiAudio.dex"
CMD="app_process -Djava.class.path=$DEX /system/bin --nice-name=multiaudio MultiAudio sysapi"
if [ "$(id -u)" = "0" ]; then exec $CMD; else exec su -c "$CMD"; fi
