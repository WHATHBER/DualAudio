#!/system/bin/sh
# 【步骤4】分别设置两个耳机的音量（解决"有线炸耳朵"）
# 直接改这两个数字即可，改完保存、执行本脚本。
USB_VOL=30      # USB 声卡/有线耳机 音量百分比 0-100
BT_VOL=80       # 蓝牙耳机 音量百分比 0-100
DIR=$(cd "$(dirname "$0")" && pwd)
DEX="$DIR/MultiAudio.dex"
run() {
  CMD="app_process -Djava.class.path=$DEX /system/bin --nice-name=multiaudio MultiAudio $1"
  if [ "$(id -u)" = "0" ]; then $CMD; else su -c "$CMD"; fi
}
echo "---- 当前各设备音量 ----"
run "vol"
echo "---- 设置 USB(有线) = $USB_VOL% ----"
run "vol usb $USB_VOL"
echo "---- 设置 蓝牙 = $BT_VOL% ----"
run "vol bt $BT_VOL"
echo "---- 设置后 ----"
run "vol"
