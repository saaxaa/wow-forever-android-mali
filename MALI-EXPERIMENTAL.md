# Xiaomi 14T: экспериментальный system Vulkan backend

На Xiaomi 14T клиент WoW Forever 1.60.1.70938 отрисовал вступительный ролик и экран входа Battle.net через DXVK 2.4.1 и системный Mali-G615 MC6. Для этого использованы внутренняя папка приложения, GameNative wrapper от 24 июля 2026 года и настройка `WRAPPER_NO_PATCH_OPCONSTCOMP=1`. Вход в игровой мир требует учётной записи и отдельной проверки.
Проверенная прошивка — HyperOS OS3.0.303.0.WNERUXM, Android 16. Для устройства с другой версией драйвера результаты могут отличаться.

Основа: `jaredgei/wow-forever-android`, commit
`62936c2399b7111b0251fa8099bef86379a3d91a`.

## Что менялось

| Файл | Изменение |
|---|---|
| `app/src/main/java/app/gamenative/ui/screen/wow/WowGraphicsBackend.kt` | Выбор Mali/Immortalis/MediaTek → System; Adreno → Turnip; неизвестный GPU не получает Turnip автоматически. |
| `app/src/main/java/app/gamenative/ui/screen/wow/WoWForeverScreen.kt` | `GpuProfile.detect`, `prepareLaunch`, `containerConfig`, `installBundledComponents`; отдельный Mali-контейнерный профиль, 720p, предзапусковая проверка, кнопка Test Vulkan, сообщения ошибок. |
| `app/src/main/java/app/gamenative/ui/screen/wow/WowVulkanDiagnostics.kt` | JNI probe, настоящий API version, ICD manifest, очистка Adreno/spoofing env, логи и извлечение причины ошибки. |
| `app/src/main/java/app/gamenative/ui/screen/xserver/XServerScreen.kt` | `extractGraphicsDriverFiles`, `setupXEnvironment`: закреплённый wrapper для Mali, отдельный cache key, DXVK/Wine logs, сравнение System по значению строки. |
| `app/src/main/java/com/winlator/xenvironment/components/BionicProgramLauncherComponent.java` | `execGuestProgram`: повторное применение Mali-политики после merge env и overlay, непосредственно перед запуском Wine. |
| `app/src/main/cpp/wow/vulkan_diagnostics.cpp` | JNI probe и ICD proxy: запись фактических результатов создания instance/device в Wine. |
| `app/src/main/cpp/wow/CMakeLists.txt` | Сборка только новой `libwow_vulkan.so`, C++17, static libc++, 16 KiB alignment. |
| `app/build.gradle.kts` | CMake и опциональная сборка `-PmaliSideBySide=true` с package ID `app.wowforever.mali`. |
| `app/src/main/AndroidManifest.xml` | Название тестовой копии через manifest placeholder. |
| `app/src/main/java/com/winlator/container/Container.java`, `app/src/main/java/com/winlator/core/WineUtils.java` | Внутренние пути используют фактический APPLICATION_ID; изолированная копия не обращается к приватному каталогу основного приложения. |
| `app/src/test/java/app/gamenative/ui/screen/wow/WowGraphicsBackendTest.kt` | Тесты Mali, Adreno и fallback SoC. |
| `app/src/test/java/app/gamenative/ui/screen/wow/WowVulkanDiagnosticsTest.kt` | Тесты итогового окружения, ICD и приоритета точного VkResult. |
| `tools/fetch_components.sh`, `tools/components.sha256` | Wrapper для Mali, проверка SHA-256, совместимость проверки сумм с Git Bash/CRLF. |
| `.github/workflows/debug-mali.yml` | JDK17, SDK/NDK, сборка, тесты, отчёт Lint и APK как Actions artifact. |
| `.gitignore` | Локальные исследовательские материалы `.work/`. |
| `MALI-EXPERIMENTAL.md`, `README.md` | Анализ, ограничения, установка, сборка и протокол проверки. |

## Цепочка запуска и выявленные зависимости

1. `WoWForeverScreen.prepareLaunch` устанавливает Proton/DXVK, проверяет игровой каталог,
   подготавливает существующий `Config.wtf`, создаёт либо обновляет `wow_forever` через
   `ContainerManager.createContainer` / `Container.loadData`. Wine-prefix и imagefs управляются
   `ContainerManager`, `ImageFsInstaller.installIfNeededFuture`, `ImageFsInstaller.installFromAssetsFuture`,
   `ImageFsInstaller.ensureProtonVersionSymlink`, `ContentsManager.applyContent`.
2. `XServerScreen` подготавливает prefix, устанавливает DXVK DLL и графический wrapper, запускает
   XServer, ALSA/PulseAudio и `BionicProgramLauncherComponent`. `WowMachineIdentity` остаётся
   исходной частью настройки prefix и не используется для маскировки GPU.
3. `BionicProgramLauncherComponent.execGuestProgram` формирует `LD_LIBRARY_PATH`, `LD_PRELOAD`,
   `WINEPREFIX`, DISPLAY, overlay и команду `wine explorer /desktop=... Wow-ARM64.exe -d3d11`.
   `ProcessHelper.exec` запускает Bionic Wine через `/system/bin/linker64` и читает stdout/stderr.
4. `Wow-ARM64.exe` вызывает ARM64 `d3d11.dll` / `dxgi.dll` DXVK. Wine winevulkan передаёт
   Vulkan-вызовы в Bionic loader, который загружает ICD по manifest.

Раньше `GpuProfile.detect` выбирал Adreno 740/Turnip для **любого** не-Adreno-8xx GPU.
`installBundledComponents` ставил обе сборки Turnip, `containerConfig` всегда задавал
`adrenotoolsTurnip=1`, `TU_DEBUG` и Mesa/Freedreno env. Это исправлено только в новой Mali-ветке.
Adreno 740 и 8xx сохраняют прежние драйверы и настройки.

Другие места, изученные при анализе:

- `GPUInformation.getRenderer(Context)` получает EGL renderer; `isAdreno*` классифицируют Qualcomm;
  JNI в `cpp/extras/vulkan.c` умеет открывать системный Vulkan и custom driver через adrenotools.
- `AdrenotoolsManager.setDriverById` задаёт `ADRENOTOOLS_DRIVER_PATH/NAME/HOOKS_PATH`.
  Эти переменные запрещены в итоговом окружении Mali, даже если они остались в старом контейнере.
- `XServerScreen.extractGraphicsDriverFiles` содержит отдельные старые Turnip/Adreno/Vortek ветки,
  `freedreno_icd` и `wrapper_icd`, а также overrides имени, vendor/device ID и API version.
Mali идёт через wrapper с API не выше системного и предела закреплённого wrapper (1.3.289), без overrides идентичности.
- `DXVKHelper.setEnvVars` выключал DXVK logs; Mali включает их после этого вызова.
- `VulkanRenderer.nativeInit`, `cpp/winlator/vulkan_jni.cpp::openAdrenotoolsDriver` и
  `VulkanRendererContext::createInstance/createLogicalDevice` отвечают за Android-композитор,
  а не за D3D11 игры. `VulkanRenderer` по умолчанию открывает stock driver, когда custom handle отсутствует.
  Эти существующие библиотеки по-прежнему поставляются upstream как prebuilt; их сборка не изменена.

## Как устроен новый backend

```text
WoW ARM64 → DXVK ARM64 → Wine winevulkan → Bionic Vulkan loader
 → libwow_vulkan.so (ICD proxy, только диагностика)
 → libvulkan_wrapper.so (существующий Vk-on-Vk / X11 WSI / BCn bridge)
 → /system/lib64/libvulkan.so (Android loader)
 → системный драйвер Mali → GPU
```

Использовать Android loader без Turnip возможно: wrapper имеет system-driver path при отсутствии
Adrenotools-переменных. Прямой desktop ICD manifest на Android `libvulkan.so` некорректен:
Android loader не является Linux desktop ICD с X11 WSI. Не требуется root и не выполняется
прямая загрузка приватных библиотек `/vendor/lib64/hw/vulkan.*.so`.

Для Mali выбран датированный архив из каталога GameNative `graphics_driver_download.json`. В APK включена проверенная версия с такой контрольной суммой.

```text
https://downloads.gamenative.app/graphics_driver/wrapper-gamenative-20260724.tzst
SHA256: 1a546621cf285b50a6de47aeb0ada4a298d41ac24fc5ca2cdc46673859056d37
```

Он включён в APK как `bundled_components/wrapper-gamenative-20260724.tzst`. Если сервер заменит файл,
`fetch_components.sh` должен завершиться ошибкой суммы: обновление зависимости требует анализа.
Собственный код wrapper, Wine и DXVK не пересобирался. Новый ICD proxy не изменяет имя GPU,
extensions, feature bits, handles или результаты драйвера; он записывает вызовы создания.
Исходный wrapper может эмулировать/объявлять desktop-возможности: это само по себе не доказывает
корректное выполнение шейдеров и не считается подтверждением поддержки Mali.

`prepare` отдельно открывает **системный Android loader**, записывает device/driver/API/extensions,
создаёт настоящий минимальный graphics `VkDevice`, вызывает `vkQueueSubmit` и ждёт fence.
PASS означает только успех этой нативной проверки. Он не означает создание D3D11-устройства,
успешную передачу кадров через X11/AHardwareBuffer или вход в игровой мир.

## DXVK 2.4.1 и ограничения

Контрольная сумма ARM64 WCP проверена. `profile.json` указывает `2.4.1-wow-aarch64-test`;
PE machine у `system32/d3d11.dll` и `dxgi.dll` равен `0xaa64` (ARM64).
На Xiaomi 14T эта цепочка создала D3D11-устройство уровня 11_0 и вывела интерфейс клиента.

Ориентир требований — исходники upstream DXVK v2.4.1; комплектная ARM64 test-сборка может содержать
дополнительные патчи, поэтому окончательный источник истины — её runtime logs и рендеринг.

| Уровень | Что проверять на устройстве |
|---|---|
| Instance/WSI | `VK_KHR_surface` и X11 surface, предоставляемый wrapper; Android-композитор использует `VK_KHR_android_surface`. |
| Device | Vulkan 1.3; обязательные в DXVK расширения `VK_KHR_swapchain`, `VK_EXT_robustness2`. |
| Core features | `robustBufferAccess`, `vulkanMemoryModel`, `hostQueryReset`, `timelineSemaphore`, `synchronization2`, `dynamicRendering`, `maintenance4`; D3D11 дополнительно использует `shaderDrawParameters`, `samplerMirrorClampToEdge`, `shaderDemoteToHelperInvocation`. |
| D3D11 | Важны BC-текстуры, geometry shader, transform feedback/geometryStreams, ограничения formats/descriptor limits. Поддержка feature level зависит от полного набора возможностей. |
| Вывод кадров | Для существующего Vulkan-композитора нужны swapchain и `VK_ANDROID_external_memory_android_hardware_buffer`; создание устройства без surface не проверяет импорт AHB/WSI. |

`VK_EXT_graphics_pipeline_library` — оптимизация, отсутствие этого расширения не является само по
себе доказательством несовместимости. Не следует требовать все EXT из desktop-драйвера сразу.

Mali часто отличается от Adreno по BC formats и desktop shader features. Wrapper обрабатывает
часть этих различий, но объявления функций недостаточно. При `VK_ERROR_EXTENSION_NOT_PRESENT`
сравнивать requested extensions и `wrapper deviceExtension`; при `VK_ERROR_FEATURE_NOT_PRESENT`
смотреть feature chains, raw capabilities и wrapper log. Если драйвер возвращает только код ошибки,
он сохраняется как есть: причина внутри закрытого vendor driver не выдумывается.

Проверка API ниже 1.3 прекращает запуск этого экспериментального DXVK 2.4.1 backend с конкретным
сообщением. Поднимать `WRAPPER_VK_VERSION` выше реальной версии и менять GPU name для обхода отказа нельзя.

Реалистичные следующие шаги при подтверждённой несовместимости:

1. Обновить HyperOS/прошивку с более новым Vulkan-драйвером и повторить тот же протокол.
2. Исследовать исправление конкретного недостатка wrapper, с проверкой features и реального рендера.
3. Более старый DXVK требует **Windows ARM64** сборки и проверки нужного игре D3D11 feature level;
   обычные x64/x86 DLL и Winlator-сборки для Box64 не являются заменой этим DLL.
4. WineD3D с рабочей ARM64 OpenGL/Zink-цепочкой — отдельный эксперимент, здесь он не реализован.
5. PanVK/Panfrost требует подходящего kernel/userspace ABI и поддержки именно этой Mali/прошивки;
   загрузить Turnip либо произвольный Mesa ICD как универсальную замену нельзя.
6. Для практической игры без исправления vendor driver — стриминг с ПК.

Требования и пути проверялись по первичным источникам:
[DXVK extensions v2.4.1](https://github.com/doitsujin/dxvk/blob/v2.4.1/src/dxvk/dxvk_extensions.h),
[DXVK device creation](https://github.com/doitsujin/dxvk/blob/v2.4.1/src/dxvk/dxvk_adapter.cpp),
[D3D11 features](https://github.com/doitsujin/dxvk/blob/v2.4.1/src/d3d11/d3d11_device.cpp),
[Android Vulkan loader](https://source.android.com/docs/core/graphics/implement-vulkan).
Публичный [Bionic Vulkan wrapper](https://github.com/leegao/bionic-vulkan-wrapper) использовался
как архитектурная справка; соответствие его текущего HEAD исходникам закреплённого бинарника не утверждается.
Параметры логирования закреплённого бинарника проверены по его strings/disassembly:
`WRAPPER_LOG_LEVEL=info,error,validation`, `WRAPPER_LOG_FILE=<path>`.

## Установка и проверка Xiaomi 14T

1. Сохранить важные настройки существующей установки. Package ID остался `app.wowforever`.
   Debug APK подписан локальным debug key; поверх официального APK с другим ключом Android
   вернёт `INSTALL_FAILED_UPDATE_INCOMPATIBLE`. Не удалять исходную установку без резервной копии
   её приватных настроек. Общий игровой каталог не входит в APK.
2. При несовпадении подписей рекомендуется установить отдельную копию:
   `adb install -r artifacts/WoW-Forever-Mali-side-by-side-debug.apk` (название **WoW Forever Mali (test)**,
   package `app.wowforever.mali`). Она не заменяет основную установку. Для обычной сборки установить через файл или `adb install -r artifacts/WoW-Forever-Mali-experimental-debug.apk`.
3. В лаунчере должна отображаться Experimental Mali / system Vulkan.
   Нажать **Test Vulkan**: для этого не нужны игровые данные или Battle.net login.
   Сохранить `system-vulkan.txt`, включая GPU name, driverInfo, API, extensions, результаты device/fence.
4. На проверенном Xiaomi 14T использовать внутреннюю копию по инструкции ниже. На уже настроенном телефоне лаунчер выбирает `files/wow-private`. В двух ранних запусках из общей папки системная служба FUSE аварийно завершилась.
   Разрешить All files access. Первой установке Wine imagefs и общих компонентов нужен интернет.
5. Нажать Play с разрешением 720p. Не включать сторонние overrides GPU/API/extensions.
   Дождаться не только меню, но и входа в мир; проверить текстуры, эффекты, перемещение камеры,
   отсутствие device lost, устойчивость 10–15 минут и возврат после сворачивания.
6. Собрать логи и записать версию HyperOS/Android, модель телефона, результат и время воспроизведения.

Игра может всё ещё показать собственное общее сообщение об ускорителе — оно находится в клиенте Blizzard
и не патчится. Лаунчер показывает причины предзапускового отказа и найденную ошибку графики после
завершения процесса. Если процесс остаётся в игровом диалоге, сначала сохранить логи, затем выйти.
При отсутствии доказанной причины лаунчер указывает, что ошибка не определена, а не выдумывает её.

## Получение логов через ADB

В PowerShell, когда `adb` из Android SDK находится в PATH. Для отдельной копии заменить
`app.wowforever` во всех командах и путях ниже на `app.wowforever.mali`:

```powershell
adb devices -l
adb shell am force-stop app.wowforever
adb logcat -c
adb logcat -v threadtime > xiaomi14t-logcat.txt
# Оставить команду работающей, открыть приложение и воспроизвести ошибку. Затем Ctrl+C.
# cmd сохраняет бинарный поток без перекодировки даже из Windows PowerShell 5.
cmd /c "adb exec-out run-as app.wowforever tar -C files -cf - graphics_logs wine_logs > xiaomi14t-logs.tar"
tar -xf xiaomi14t-logs.tar
adb shell getprop ro.build.fingerprint
adb shell getprop ro.soc.model
```

В новой Debug-сборке Mali оба каталога журналов находятся внутри `context.filesDir`.
В предыдущей сборке они находились в `/sdcard/Android/data/<package>/files/`.

- `graphics_logs/system-vulkan.txt`: raw system probe, GPU, vendor/device ID, driver raw version/name/info,
  API, instance/device extensions, часть features, точные результаты вызовов.
- `graphics_logs/mali-wrapper-icd.json`: фактический путь tracing ICD и реальный API.
- `graphics_logs/vulkan-calls.log`: фактический Wine/wrapper instance/device result, requested/exposed extensions.
- `graphics_logs/wrapper.log`: внутренние сообщения закреплённого Bionic wrapper.
- `graphics_logs/*_d3d11.log`, `*_dxgi.log`: DXVK, выбранный adapter, feature level, ошибки D3D11.
- `graphics_logs/wine-proton.log`, `wine_logs/wine_debug.log`: stdout/stderr Wine/Proton.
- Logcat tags `WoWVulkan`, `WoWWine`, `VulkanRenderer`, `Winlator_Renderer`, `BionicProgramLauncherComponent`.
- Дополнительно сохранить игровой `_forever_/Logs/gx.log`, если он создан.

Файлы `.log` graphics session очищаются перед следующим Play. Сначала скопировать результаты!
В режиме диагностики logs могут быть большими; они могут содержать пути, имя персонажа и другие
данные из Wine/игры. Просмотреть их перед публичной публикацией.

## Воспроизводимая сборка

Установить JDK17, Android SDK platform36/build-tools35.0.0, NDK27.3.13750724, CMake3.22.1.
Пример Windows (Git Bash нужен только для fetch script):

```powershell
$env:JAVA_HOME = 'C:\Program Files\Eclipse Adoptium\jdk-17.0.15.6-hotspot'
$env:ANDROID_HOME = "$env:LOCALAPPDATA\Android\Sdk"
Set-Content local.properties ('sdk.dir=' + $env:ANDROID_HOME.Replace('\','/'))
& 'C:\Program Files\Git\bin\bash.exe' tools/fetch_components.sh
.\gradlew.bat --no-daemon :app:assembleModernDebug
.\gradlew.bat :app:testModernDebugUnitTest --tests '*WowGraphicsBackendTest' --tests '*WowVulkanDiagnosticsTest'
```

Результат: `app/build/outputs/apk/modern/debug/app-modern-debug.apk`.
Для установки рядом с официальной версией добавить `-PmaliSideBySide=true` к командам Gradle.
В этой сборке label — WoW Forever Mali (test), package — app.wowforever.mali.
Для DXVK logs контейнер явно монтирует L: на graphics_logs: Z: в исходном WineUtils указывает на imagefs, а не корень Android.
Все пять runtime archives должны пройти SHA-256. Игра Blizzard в APK не включена.
Исходные правила `Config.wtf` оставлены: D3D11 и разрешение, без патчей EXE/игровых архивов.

GitHub Actions: отправить этот код в свой fork, открыть Actions → Experimental Mali Debug APK → Run workflow.
Загрузить artifact `WoW-Forever-Mali-experimental-debug`. Ключи release signing не нужны.


## Проверки этой сборки

SHA-256 проверенной сборки указан в [релизе Xiaomi 14T](https://github.com/saaxaa/wow-forever-android-mali/releases/tag/v2.4.2-mali.1). Восемь профильных JVM-тестов прошли.
Новые JVM/Robolectric тесты проверяют routing, окружение, отсутствие spoofing и диагностические ошибки;
они не исполняют ARM64 Vulkan-драйвер. Компиляция native C++ проходит с `-Wall -Wextra -Werror`.

В расширенном наборе существующих WoW-тестов обнаружен Windows-specific отказ
`WowMachineIdentityTest.applyTo_replacesTheSharedPrefixIdentity`. Он затрагивает исходный
`WineRegistryEditor` (замена файлов через `File.renameTo`) и не относится к Mali backend.
Проверка Lint целиком также не зелёная: 10 ошибок существующего кода (TV manifest features,
API30 `Context.display` при minSdk29, WrongConstant в ALSA/VulkanRenderer). Правила не отключались.
В Actions Lint сохраняет отчёт как неблокирующий шаг; сборка и тесты остаются обязательными.

На Xiaomi 14T system probe подтвердил Vulkan 1.3.247, Mali-G615 MC6, создание VkInstance/VkDevice и выполнение команды с fence. Регрессия на настоящем Snapdragon требует отдельного устройства.

## Дополнение: проверка игрового запуска 15 ноября 2026

В двух запусках подтверждён сбой системного MediaProvider/FUSE (`NodeTracker::CheckTracked`,
`active_nodes_.find(node) != active_nodes_.end()`). `vold` пишет `Killing all processes referencing
/storage/emulated/0` и явно завершает `Wow-ARM64.exe`. Wine возвращает 137, лаунчер возвращается
на стартовый экран. Конкретный запрос, вызывающий сбой FUSE, пока не установлен.

В ходе проверки исправлен собственный ICD proxy: теперь он согласует loader interface v5
с реальным wrapper и передаёт `vk_icdGetPhysicalDeviceProcAddr`. Это подтверждено в runtime.
Убрана полная SEH-трассировка и отключён отсутствующий необязательный util layer.
На телефоне установлен обновлённый отдельный APK; 8 новых тестов прошли.
DXVK запрашивает Vulkan 1.3, создаёт VkDevice и выбирает D3D11 feature level 11_0
после неуспешной попытки 11_1. Началась компиляция compute shader, но FUSE-recovery
прерывает процесс. Нельзя считать проверенную инициализацию доказательством игрового рендера.

Требования loader interface: [Khronos Loader Driver Interface](https://github.com/KhronosGroup/Vulkan-Loader/blob/main/docs/LoaderDriverInterface.md#loader-and-driver-interface-version-5-requirements).

### Внутренняя папка и ранние испытания с исходным wrapper

Для отдельной Debug-установки `app.wowforever.mali` каталог `files/wow-private` внутри приложения может содержать копию клиента. Сохранённый вручную выбор папки имеет приоритет. При запуске из внутренней копии на Mali диски G: и L: ведут к внутренним игровым данным и журналам. D: и E: указывают на внутренний каталог контейнера. Исходная папка в общей памяти остаётся доступна официальному приложению. Удаление тестового приложения удалит и его частную копию.

На подключённом телефоне копия уже подготовлена. Для чистой Debug-установки, где ещё нет `wow-private` и `wow-private.incoming`, можно создать её командой ниже. Сначала сравните свободное место (`adb shell df -k /data`) и размер исходного клиента. Команда выполняет передачу между процессами на телефоне и выбирает новое имя только после успешного копирования.

```powershell
adb shell 'set -o pipefail; run-as app.wowforever.mali mkdir files/wow-private.incoming && (cd /storage/emulated/0/WoW\ Forever && tar -cf - .) | run-as app.wowforever.mali tar -C files/wow-private.incoming -xf - && run-as app.wowforever.mali mv -T files/wow-private.incoming files/wow-private'
```

Для этого опыта отдельно сопоставлены SHA-256 всех 1 563 файлов копии и оригинала. В новом `gx.log` появилась строка `Dx11 Device Create Successful (583.9 ms)`. DXVK сообщил о четырёх изображениях swapchain размером 1432×644.

В двух запусках из внутренней копии прежний сбой FUSE не повторился. Оба раза DXVK отклонил создание shader resource view формата 74 (`DXGI_FORMAT_BC2_UNORM`). WoW записал `CreateShaderResourceView Failed (HR:0x80070057)`, переключил цель рендера на RGBA16Float 1432×644, дважды сообщил о потере графического контекста и завершился. При восстановлении также зафиксирован `ClusteredShading pipeline create failure`. На повторном запуске виден чёрный экран с курсором. Меню и игровой мир не подтверждены. Причинная связь между ошибкой BC2 и потерей контекста требует отдельной проверки на уровне wrapper и DXVK. В окружении запущенного процесса наблюдались `WRAPPER_EMULATE_BCN=3` и `WRAPPER_BCN_GPU=0`.

### Проверка с GameNative wrapper от 24 июля 2026 года

В отдельной ARM64-программе под тем же Wine/DXVK удалось создать BC2-текстуру и её shader resource view. Пиксель, прочитанный обратно из RGBA16Float после отрисовки одноцветного треугольника и после выборки из BC2, в обоих случаях совпал с ожидаемым непрозрачным красным. Четыре вызова Present вернули S_OK.

В контрольном запуске WoW с этой версией wrapper Wine зафиксировал assertion в вызове vkCreateShaderModule. В рабочем профиле отключено преобразование составных констант SPIR-V в wrapper (`WRAPPER_NO_PATCH_OPCONSTCOMP=1`), DXVK ведёт журнал на уровне info. Работу проверяли на физическом Xiaomi 14T под HyperOS OS3.0.303.0.WNERUXM, со штатным драйвером Vulkan 1.3.247. На экране телефона зафиксированы ролик с субтитрами и форма входа Battle.net с отрисованным порталом. Выбор персонажа, игровой мир и длительная стабильность остаются непроверенными.

Для ручного входа открыть **WoW Forever Mali (test)** и нажать **Play World of Warcraft**. Если экранная клавиатура не открылась при касании поля, вызвать боковое меню Android-жестом «Назад» и выбрать **Keyboard**. Ввести данные Battle.net на телефоне.
