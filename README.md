Tilt (rainbow) Hat Android Things (that) - A Tilt Hydrometer monitor.


TODO:
 - Document and format this MD :-)
 v  Real Tilt integration - for now it only takes dumpsys commands
 - Use 2nd touch on Button C to start / stop timer
 - Post results to Google Drive
 - Use rainbow to display OG -> FG progress.
 - Real activity to display data when plugged to HDMI:
   - IP address
   - Temperature (C / F)
   - Gravity
   - Timer
 - Companion Android App
   - Sets parameters
   - Sync measured values
   - Calibration
 - Figure out how to grant BT permissions automatically, without:
   adb shell pm grant net.felipeal.that android.permission.ACCESS_COARSE_LOCATION
     or
   adb shell pm grant net.felipeal.that android.permission.ACCESS_FINE_LOCATION
 - Add dumpsys commands to:
   - change scan frequency
   - start scan
   - stop scan
   - change Tilt color
 - Support multiple Tilts
 - Add options to calibrate gravity and/or temperature
