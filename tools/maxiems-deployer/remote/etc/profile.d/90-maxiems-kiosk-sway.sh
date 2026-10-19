# Managed by MAXiEMS BMS kiosk.
# Start Sway only for admin's physical tty1 login. SSH and existing graphical
# sessions must never be converted into kiosk sessions.
maxiems_kiosk_tty=$(/usr/bin/tty 2>/dev/null) || maxiems_kiosk_tty=
if [ "$(/usr/bin/id -un)" = admin ] && \
    [ -z "${SSH_CONNECTION:-}" ] && \
    [ -z "${SSH_TTY:-}" ] && \
    [ "$maxiems_kiosk_tty" = /dev/tty1 ] && \
    [ -z "${WAYLAND_DISPLAY:-}" ] && \
    [ -z "${DISPLAY:-}" ]; then
    unset maxiems_kiosk_tty
    exec /usr/bin/sway
fi
unset maxiems_kiosk_tty
