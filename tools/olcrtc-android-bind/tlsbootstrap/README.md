# Android Go TLS Bootstrap

This module is bound into the **same AAR and Go runtime** as olcRTC. Android
`Os.setenv` updates libc, but not Go's environment snapshot, even when called
before the JNI library is loaded. Calling Go `os.Setenv` is required.

`Configure` accepts only the pinned public GlobalSign R46 CA, sets Go's
`SSL_CERT_FILE`, then verifies that the resulting system pool trusts that CA.
It does not replace Android's certificate directories, alter the system trust
store, or disable TLS hostname, certificate, or expiry verification. Call it
from `Application.attachBaseContext` before starting olcRTC.

Run focused tests from this directory:

```sh
go test -mod=readonly ./...
go vet ./...
```

The Gradle AAR task creates a local `file://` module proxy from `go.mod` and
`roots.go` with normalized LF endings. The bind module requires version
`v0.0.1`; its checksum is pinned in `go.sum` and the Gradle task. Upstream
olcRTC remains unchanged. This avoids an absolute local `replace` path in Go
build metadata. The checksum database exception covers this local module only.

When changing runtime code, bump the local module version and review the new
checksum. The build verifies both the selected dependency and its cached source
against these files; it rejects stale versions and unexpected replacements.
The public certificate in `testdata` is a test fixture, not a user certificate.

## Русский

Модуль собирается в тот же AAR и тот же Go runtime, что и olcRTC. Вызов Android
`Os.setenv` меняет окружение libc, но не копию окружения Go, даже до загрузки JNI.
Поэтому путь к сертификату передаётся через Go `os.Setenv`.

`Configure` принимает только закреплённый по SHA-256 публичный корень GlobalSign
R46. Системные каталоги сертификатов, проверка имени сайта и срока действия
сохраняются. Хранилище сертификатов устройства не меняется. Инициализация должна
выполняться в `Application.attachBaseContext` до запуска olcRTC.

Gradle создаёт локальный файловый module proxy. Версия и контрольная сумма
обёртки закреплены; исходники olcRTC не меняются. В native-метаданные не попадает
абсолютный путь рабочей папки. При изменении `roots.go` нужно повысить версию
этого локального модуля и проверить новую контрольную сумму. Команды тестов
приведены выше; файл в `testdata` содержит публичный CA, а не пользовательские
данные.
