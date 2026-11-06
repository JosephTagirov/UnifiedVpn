"""Guard standby identity administration while retaining local session exports."""
import os
import re


MESSAGE = "Резервный сервер доступен для просмотра и экспорта. Изменения выполняются на основном сервере после восстановления связи."


def enabled():
    return os.environ.get("UVPN_CONTROL_READ_ONLY") == "1"


def allowed_post(path, prefix="/vpn-control"):
    session_paths = {"/auth/login", "/auth/logout", "/claim"}
    read_paths = {"/api/telegram/check", "/api/telegram/export", "/api/telegram/web-export"}
    if path in {prefix + suffix for suffix in session_paths | read_paths}:
        return True
    return re.fullmatch(re.escape(prefix) + r"/api/(?:devices|existing)/[a-f0-9]{32}/export", path) is not None
