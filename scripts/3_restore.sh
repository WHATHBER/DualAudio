#!/system/bin/sh
# 【步骤3】恢复原状：清除媒体策略上的多设备绑定
DIR=$(cd "$(dirname "$0")" && pwd)
DEX="$DIR/MultiAudio.dex"
CMD="app_process -Djava.class.path=$DEX /system/bin --nice-name=multiaudio MultiAudio autoclear"
if [ "$(id -u)" = "0" ]; then exec $CMD; else exec su -c "$CMD"; fi
