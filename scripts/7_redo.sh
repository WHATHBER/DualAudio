#!/system/bin/sh
# 【一键重来】蓝牙断开重连后，跑这一个脚本即可重建双设备输出并设好两路音量。
# 直接改下面两个数字即可。
USB_VOL=10      # USB 声卡/有线耳机 音量百分比 0-100（炸耳朵就往小改）
BT_VOL=80       # 蓝牙耳机 音量百分比 0-100
DIR=$(cd "$(dirname "$0")" && pwd)
DEX="$DIR/MultiAudio.dex"
run() {
  CMD="app_process -Djava.class.path=$DEX /system/bin --nice-name=multiaudio MultiAudio $1"
  if [ "$(id -u)" = "0" ]; then $CMD; else su -c "$CMD"; fi
}
echo "==== 1/4 当前设备与策略 ===="
run "list"
echo "==== 2/4 建立双设备同时输出 ===="
run "auto"
echo "==== 3/4 设置 USB(有线) 音量 = $USB_VOL% ===="
run "vol usb $USB_VOL"
echo "==== 4/4 设置 蓝牙 音量 = $BT_VOL% ===="
run "vol bt $BT_VOL"
echo "==== 全部完成。请【暂停音乐 → 重新播放】 ===="
