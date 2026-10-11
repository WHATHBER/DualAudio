#!/system/bin/sh
# 【调 USB 声卡硬件音量】解决"有线耳机炸耳朵"
# 直接改下面这个数字：0-100，越小越轻。改完保存、执行本脚本。
VOL=30
if [ "$(id -u)" = "0" ]; then
  echo "---- 设置前 ----"
  tinymix -D 1 "PCM Playback Volume"
  tinymix -D 1 "PCM Playback Volume" $VOL $VOL
  echo "---- 设置后 ----"
  tinymix -D 1 "PCM Playback Volume"
else
  echo "---- 设置前 ----"
  su -c "tinymix -D 1 'PCM Playback Volume'"
  su -c "tinymix -D 1 'PCM Playback Volume' $VOL $VOL"
  echo "---- 设置后 ----"
  su -c "tinymix -D 1 'PCM Playback Volume'"
fi
echo "（建议：30 试听 → 太大改 15 → 太小改 50）"
