#!/system/bin/sh
# MultiAudio 一键运行脚本（root / KernelSU）
# 用法:
#   把 MultiAudio.dex 和本脚本放到同一目录（如 /data/local/tmp/）
#   sh multiaudio.sh list
#   sh multiaudio.sh set 1 8:AA:BB:CC:DD:EE:FF 22:card=1\;device=0
#   sh multiaudio.sh clear 1
DIR=$(cd "$(dirname "$0")" && pwd)
DEX="$DIR/MultiAudio.dex"

if [ "$(id -u)" != "0" ]; then
  exec su -c "app_process -Djava.class.path=$DEX /system/bin --nice-name=multiaudio MultiAudio $@"
else
  exec app_process -Djava.class.path="$DEX" /system/bin --nice-name=multiaudio MultiAudio "$@"
fi
