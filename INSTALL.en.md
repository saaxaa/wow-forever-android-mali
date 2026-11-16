# WoW Forever on Xiaomi 14T

[Русский](INSTALL.ru.md) · [Download the APK](https://github.com/saaxaa/wow-forever-android-mali/releases/download/v2.4.3-mali.1/WoW-Forever-Mali-side-by-side-debug.apk)

On a Xiaomi 14T with Mali-G615 MC6, Android 16 and HyperOS OS3.0.303.0.WNERUXM, the client reached the Battle.net login screen on two launches. The opening cinematic and login screen rendered with system Vulkan 1.3.247 and DXVK 2.4.1. With a saved login, version 2.4.3-mali.1 reached Battle.net’s WoW license selector. Confirming the Classic preset opened the character screen with **Enter World**. World entry and sustained gameplay still need testing.

To upgrade from the first Mali release, install the linked APK over your existing copy once. This keeps your game data and saved login. Starting with 2.4.3-mali.1, the launcher checks this fork for updates, including Mali prereleases. Use **Refresh** to check both the launcher and game versions.

### Install and test Vulkan

1. Download the APK above. Open it on the phone and allow installation from the selected source. To install from a computer, enable USB debugging, connect the phone and run the commands below from the folder containing the APK. Confirm the USB installation prompt on the phone if HyperOS shows one.
2. Open **WoW Forever Mali (test)**. Its package name is `app.wowforever.mali`, so it can coexist with the original launcher.
3. Tap **Test Vulkan**. This check works without game data or an account. The report includes the GPU, driver version, extensions and the result of Vulkan device creation. If the test fails, save the report using the command at the end of this guide.

```sh
adb devices -l
adb install -r WoW-Forever-Mali-side-by-side-debug.apk
```

### Prepare the game and launch it

1. On a fresh installation, grant file access and tap **Download & Install**. Keep the default folder `/storage/emulated/0/WoW Forever`. If it already contains the client, the launcher can detect it. A folder selected manually through **Location** takes priority over automatic detection of the private copy. Initial setup and game data streaming need an internet connection. Players obtain Blizzard game files separately.
2. On the tested 14T, the game process exited when reading from shared storage. To reproduce the working setup, use a private copy. If you already have one, continue to step 3. For a fresh copy, wait for the client download, including `_forever_/Wow-ARM64.exe`, to finish. Run the following in PowerShell or Bash. Compare the folder size in KiB with the space available on `/data` before copying. The private copy takes additional space and Android removes it when you uninstall this app.

```sh
adb shell am force-stop app.wowforever.mali
adb shell 'du -sk /storage/emulated/0/WoW\ Forever && df -k /data'
adb shell 'set -o pipefail; run-as app.wowforever.mali mkdir files/wow-private.incoming && (cd /storage/emulated/0/WoW\ Forever && tar -cf - .) | run-as app.wowforever.mali tar -C files/wow-private.incoming -xf - && run-as app.wowforever.mali mv -T files/wow-private.incoming files/wow-private'
```

3. Reopen the app. Check that the game path ends in `files/wow-private`. Select **720p** and tap **Play**. The first launch prepares Wine. Testing beyond the login screen requires your own account with access to WoW Forever.

### Choose a WoW license on the Select account screen

Tap the row for your WoW license. Once it turns blue, tap **Accept**. If tapping the row does not change the selection, use the Android **Back** gesture to open the quick menu. Select **Controller**, scroll down and enable **Touchscreen Mode**. Close the menu with **Back** and tap the license again. The container remembers this setting.

### Confirm Classic or Enhanced

On **Choose Your Experience Preset**, the gold outline shows the selected preset. The **A Select** prompt refers to the gamepad button. If the buttons are hidden, use the Android **Back** gesture, choose **Controller** and enable the on-screen controller. Close the menu with the **×**, then press the round **A** button on the right, below **Y**. On the tested Xiaomi 14T this confirmed **Classic** and opened the screen with **Enter World**.

### If the game returns to the launcher

Save the last session's logs before pressing **Play** again. Run the first two commands in Windows PowerShell. On Linux or macOS, replace the second command with the line below the block.

```powershell
adb logcat -d -v threadtime > xiaomi14t-logcat.txt
cmd /c "adb exec-out run-as app.wowforever.mali tar -C files -cf - graphics_logs wine_logs > xiaomi14t-logs.tar"
```

```sh
adb exec-out run-as app.wowforever.mali tar -C files -cf - graphics_logs wine_logs > xiaomi14t-logs.tar
```

If you only ran **Test Vulkan**, read its report with `adb shell run-as app.wowforever.mali cat files/graphics_logs/system-vulkan.txt`. For a game failure, look in the archive under `graphics_logs` for `vulkan-calls.log`, `*_d3d11.log` and `wine-proton.log`. Include your phone model, HyperOS version and last visible screen in an issue. Remove email addresses, character names and other personal details from excerpts before posting. [Technical report (Russian)](MALI-EXPERIMENTAL.md) · [Report an issue](https://github.com/saaxaa/wow-forever-android-mali/issues)
