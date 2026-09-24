# Upstream Refresh: 2026092103

Prepared on 2026-09-21 for Unified VPN 0.0.14 Preview. No production server,
working host VPN, proxy, DNS or route is changed by this update.

## Selected Versions

| Component | Previous | Selected |
| --- | --- | --- |
| olcRTC | `f616f57bb3a90740f1755922ffeaa7acc5cfe4ed` | `d7a00da5242f72a48b505ffc4b6aa8246376bf25` |
| AWG-capable Throne sing-box | `cbe7088a0723478f609388c00cf866c0971f5e58` | `7745e9afd0a1f7a5b6216fd114e1ad4a64b974c3` |
| Embedded Throne AmneziaWG | `e1dd521889a68b005372996eacb4993597af729d` | `b311c8ac53aed5637b0bd961b9c6ae1fc1eef093` |

The latest published original [olcbox 1.0.129-nightly](https://github.com/alananisimov/olcbox/releases/tag/1.0.129-nightly)
uses its [olcRTC fork at 08843d6](https://github.com/alananisimov/olcrtc/commit/08843d6accd7f43a1d04aa0ac7a3d5ea90e27efa).
The newer olcbox main revision is `dc891d8cb0c5220f8204b71f50b03943168e2fab`.
We port the relevant readiness/cancellation and same-room settling changes,
not the entire UI/dependency tree or Amnezia VPN management application.

## Why Not The Newest olcRTC

The newest core was tested first. Commit
[`14fac077`](https://github.com/openlibrecommunity/olcrtc/commit/14fac077)
changes the outer Jitsi EndpointMessage from top-level `raw` to
`msgPayload.raw`. The new receiver reads both; the old receiver only reads the
former. This is separate from the OLC2 encrypted record layer, which is unchanged.

The compatibility test uses the actual new sender serializer and each actual
receiver through Go overlays. The old receiver returns zero bytes for the
42-byte new-format fixture. Seven assertions establish this matrix:

| Receiver | Old envelope | New envelope |
| --- | --- | --- |
| Latest `08843d6` | Accepted | Accepted |
| Previous `f616f57` | Accepted | Rejected |
| Selected `d7a00da` | Accepted | Rejected |

`d7a00da` is the direct ancestor before the incompatible change. Its Jitsi
bridge source is identical to the former client, while it includes Goolom
mid-handshake reconnect/session cleanup fixes. It does not include later
reconnect-serialization, VP8/KCP or restored mobile LogWriter changes.
This choice avoids requiring a simultaneous server upgrade. It does not claim
compatibility with servers using the pre-OLC2 record layer.

Reproduce with already downloaded, clean standalone clones and cached Go modules:

```powershell
pwsh -NoProfile -File scripts/Test-OlcRtcCoreCompatibility.ps1 `
  -LatestSource .downloads/upstream-review-20260921/olcrtc-olcbox-fork `
  -LegacySource .downloads/olcrtc-f616-source `
  -CompatibleSource .downloads/upstream-review-20260921/olcrtc-compatible-d7a00da
```

The latest candidate passed 238 top-level Go tests plus 31 subtests, and the
selected candidate passed 234 plus 31, each normally and with `-race`.
Ten packages cover mobile, crypto, handshake, mux, client, server, runtime,
Jitsi, Goolom and VP8. Real paired-Jitsi tests remain explicitly gated/skipped.
These offline results do not prove delivery through any particular public Jitsi
bridge. Separate live client results are recorded in the
[publication report](release-0.0.14-2026092103.md).

## AWG Update

The selected [Throne source](https://github.com/Throneproj/sing-box/tree/7745e9afd0a1f7a5b6216fd114e1ad4a64b974c3)
is from the development branch `wip/1.14.0`, not a stable release. The repository's
default stable branch is older than our previous pin and is not used as a
"latest" shortcut. The embedded [AWG fix](https://github.com/Throneproj/wireguard-go/commit/b311c8ac53aed5637b0bd961b9c6ae1fc1eef093)
corrects reserved-byte processing. Adapters now preserve AWG 3.1
`RandomTrailers`, `DisableCookies` and validated keepalive ranges.

Windows amd64 and Android arm64/ARMv7/x86_64 were built twice to different
directories with identical SHA256. Build verification checks the clean source
revision, Go 1.26.4, target, build tags and versioned backend checksum.
Android additionally passes structured LLVM checks for API 23, ET_DYN/PIE,
architecture, interpreter and 16KiB-compatible LOAD segments (NDK 28.2.13676358).

Seventeen config-only checks cover plain WG, AWG 1/2/3/3.1, IPv6, private/custom
DNS ports, numeric/ranged keepalive, six VLESS transports and the TCP-only
SOCKS/DoH bridge. Android's sing-box is shared by these adapters, so it is not
treated as an AWG-only change. No live server configuration is used.

```powershell
./tools/Build-AwgCore.ps1 -SourcePath <clean-pinned-checkout> `
  -OutputDirectory <new-output-directory> -AndroidNdk <NDK-28.2.13676358>
./tools/Test-AwgCoreConfigs.ps1 -Binary <candidate-exe> `
  -OutputDirectory <new-synthetic-fixture-directory>
```

Gradle independently rejects stale/modified AWG binaries, wrong targets,
missing build tags or the wrong embedded backend. Existing build 2026092102
APK/EXE and native inputs are retained locally for rollback; live app files are
not replaced during packaging. Reverting AWG 3.1-specific settings to an old
core is not guaranteed to work without removing those new settings.

# Обновление Компонентов: 2026092103

Подготовлено 21.09.2026 для Unified VPN 0.0.14 Preview. Версии закреплены в таблице
выше. Рабочие серверы, VPN ноутбука, системный прокси, DNS и маршруты не менялись.

Сначала проверено свежее ядро оригинального olcbox 1.0.129 `08843d6`. Тест
настоящего сериализатора и декодеров обнаружил несовместимость: старый сервер
`f616` читает поле `raw`, но не новый `msgPayload.raw`. Вместо 42 байт он получает
ноль. Это внешний формат Jitsi, не изменение шифрования OLC2. Новый декодер
понимает оба формата; старый и выбранный совместимый понимают только прежний.
Поэтому взят непосредственно предшествующий `d7a00da` с исправлениями очистки и
переподключения Goolom. Более поздние исправления reconnect/VP8/LogWriter в него
не входят. Совместимость с серверами до OLC2 не заявляется.

Перенесены ожидание готовности Android до 60 секунд с отменяемыми интервалами
по 200 мс и задержка очистки только для повторного входа в ту же комнату.
Сохранены собственные блокировки и последовательная остановка Unified VPN.
Исходное приложение olcbox целиком и интерфейс управления Amnezia не копируются.

Свежий кандидат прошёл 238 Go-тестов и 31 подтест, выбранный совместимый 234 и
31; оба набора пройдены также с `-race`. Отдельный воспроизводимый тест форматов
выше прошёл семь проверок. Настоящие парные соединения в Go-наборе не запускались;
такие тесты отключены явно. Успех офлайн-проверки не равен проверке конкретного
сайта Jitsi или личного серверного профиля. Отдельные настоящие проверки клиентов
описаны в [отчёте публикации](release-0.0.14-2026092103.md).

AWG обновлён до экспериментальной ветки Throne `wip/1.14.0`, commit `7745e9a`,
с реализацией AWG `b311c8ac`. Она исправляет обработку reserved bytes. Добавлены
параметры AWG 3.1 `RandomTrailers`/`DisableCookies` и проверяемые диапазоны
keepalive. Это не стабильный релиз. Бот следит именно за используемой веткой,
а не более старой веткой по умолчанию.

Windows amd64 и три Android ABI собраны дважды, SHA256 совпали. Проверены
commit, Go 1.26.4, архитектуры, теги сборки и checksum AWG-модуля. Для Android
проверены API 23, ELF PIE/DYN, архитектура, interpreter и LOAD-сегменты для
страниц 16 КБ, NDK 28.2.13676358. Семнадцать проверок конфигураций охватывают
WG/AWG 1/2/3/3.1, IPv6, DNS, keepalive, шесть транспортов VLESS и SOCKS/DoH-мост.
Ядро sing-box используется и другими адаптерами Android, поэтому проверка
не ограничена одним AWG. Личные профили не задействованы.

Gradle теперь отказывает при устаревшем бинарнике, неверной архитектуре,
изменённых исходниках, отсутствующих тегах или чужой версии AWG-модуля.
Прежние APK/EXE и исходные бинарники сборки 2026092102 сохранены локально для
отката. Упаковка не заменяет запущенное приложение. Параметры AWG 3.1 могут
потребовать удаления перед возвратом к старому ядру.
