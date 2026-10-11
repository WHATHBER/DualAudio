#!/system/bin/sh
# 【抓崩溃日志】先在 App 里复现一次闪退，然后立刻执行本脚本。
# 结果会写到 /sdcard/DualAudio_crash.txt
OUT=/sdcard/DualAudio_crash.txt
echo "=== DualAudio crash report ===" > "$OUT"
echo "--- crash buffer ---" >> "$OUT"
logcat -b crash -d >> "$OUT" 2>&1
echo "" >> "$OUT"
echo "--- 关键字过滤 (com.dualaudio / AndroidRuntime) ---" >> "$OUT"
logcat -d -t 800 | grep -i -E "dualaudio|AndroidRuntime|FATAL" >> "$OUT" 2>&1
echo "" >> "$OUT"
echo "--- 本应用私有目录 ---" >> "$OUT"
ls -l /data/data/com.dualaudio/files/ >> "$OUT" 2>&1
cat /data/data/com.dualaudio/files/crash.txt >> "$OUT" 2>&1
echo "" >> "$OUT"
echo "=== 完成，文件已写入 $OUT ==="
ls -l "$OUT"
