#!/system/bin/sh
# 【备用】列出 USB 声卡(card=1) 的 ALSA mixer 控件
# 如果 4_volume.sh 降不下有线音量，用这个看硬件音量在哪一项，
# 再执行: MultiAudio alsa "控件名" 数值   即可直接调硬件音量。
DIR=$(cd "$(dirname "$0")" && pwd)
DEX="$DIR/MultiAudio.dex"
CMD="app_process -Djava.class.path=$DEX /system/bin --nice-name=multiaudio MultiAudio alsa"
if [ "$(id -u)" = "0" ]; then exec $CMD; else exec su -c "$CMD"; fi
