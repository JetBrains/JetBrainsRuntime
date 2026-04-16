# @test
# @key headful
#
# @run shell Env.sh

echo "Environment"
set
echo "==============================================================="
echo

echo ls -la  $XDG_RUNTIME_DIR/
ls -la  $XDG_RUNTIME_DIR/
echo "==============================================================="
echo

echo systemctl --user status
systemctl --user status
echo "==============================================================="
echo


echo systemctl list-units --all
systemctl list-units --all
echo "==============================================================="
echo


echo DBUS_SESSION_BUS_ADDRESS=$DBUS_SESSION_BUS_ADDRESS
BUS=$(echo $DBUS_SESSION_BUS_ADDRESS| cut -d= -f 2)
echo file $BUS
file $BUS


echo DBus ListNames
dbus-send --print-reply --session  --dest=org.freedesktop.DBus --type=method_call /org/freedesktop/DBus org.freedesktop.DBus.ListNames

echo systemctl --user status xdg-desktop-portal
systemctl --user status xdg-desktop-portal

echo DBus query
dbus-send --print-reply --dest=org.freedesktop.portal.Desktop /org/freedesktop/portal/desktop org.freedesktop.portal.Settings.Read string:'org.freedesktop.appearance' string:'color-scheme'

