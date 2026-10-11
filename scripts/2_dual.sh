#!/system/bin/sh
# 【步骤2】一键开启双设备同时输出
# 自动挑选：蓝牙 A2DP 耳机 + USB 声卡，绑定到媒体策略。
# 跑完后请【暂停音乐再重新播放】，路由才会真正切换。
DIR=$(cd "$(dirname "$0")" && pwd)
DEX="$DIR/MultiAudio.dex"
CMD="app_process -Djava.class.path=$DEX /system/bin --nice-name=multiaudio MultiAudio auto"
if [ "$(id -u)" = "0" ]; then exec $CMD; else exec su -c "$CMD"; fi
