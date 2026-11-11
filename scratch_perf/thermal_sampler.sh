SER=RZCY110ZVVE
echo "epoch_ms,status,headroom,batt_temp_dC,charge_uah,usb"
for i in $(seq 1 360); do
  now=$(adb -s $SER shell date +%s%3N 2>/dev/null | tr -d '\r')
  st=$(adb -s $SER shell dumpsys thermalservice 2>/dev/null | grep -i "Thermal Status" | head -1 | grep -o '[0-9]*')
  hr=$(adb -s $SER shell cmd thermalservice headroom 5 2>/dev/null | tail -1 | grep -oE '[0-9]+\.[0-9]+')
  bt=$(adb -s $SER shell dumpsys battery 2>/dev/null | grep -i "temperature" | grep -oE '[0-9]+' | head -1)
  cc=$(adb -s $SER shell cat /sys/class/power_supply/battery/charge_counter 2>/dev/null | tr -d '\r')
  usb=$(adb -s $SER shell dumpsys battery 2>/dev/null | grep -i "USB powered" | grep -oE 'true|false')
  echo "$now,$st,$hr,$bt,$cc,$usb"
  sleep 2
done
