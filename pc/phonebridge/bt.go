package main

// Bluetooth direct: one RFCOMM service per audio stream (a phone can't open two connections to
// the same channel), registered with BlueZ over D-Bus. BlueZ hands us each connection's socket.

import (
	"fmt"
	"os"
	"strings"

	"github.com/godbus/dbus/v5"
)

var btUUID = map[byte]string{
	roleSpk:   "a8f3c5d0-5b1e-4c7a-9e2d-7f10c0de0001",
	roleMic:   "a8f3c5d0-5b1e-4c7a-9e2d-7f10c0de0002",
	roleAudio: "a8f3c5d0-5b1e-4c7a-9e2d-7f10c0de0004",
}

type btProfile struct {
	b    *bridge
	role byte
}

func (p *btProfile) Release() *dbus.Error { return nil }

func (p *btProfile) NewConnection(dev dbus.ObjectPath, fd dbus.UnixFD, props map[string]dbus.Variant) *dbus.Error {
	f := os.NewFile(uintptr(fd), "rfcomm")
	label := "bluetooth " + strings.ReplaceAll(strings.TrimPrefix(string(dev)[strings.LastIndex(string(dev), "/")+1:], "dev_"), "_", ":")
	go p.b.serveStream(f, map[byte]bool{p.role: true}, label)
	return nil
}

func (p *btProfile) RequestDisconnection(dev dbus.ObjectPath) *dbus.Error { return nil }

// btAdapter returns the first adapter's address and whether it is powered.
func btAdapter(conn *dbus.Conn) (path dbus.ObjectPath, addr string, powered bool, err error) {
	var objs map[dbus.ObjectPath]map[string]map[string]dbus.Variant
	err = conn.Object("org.bluez", "/").Call("org.freedesktop.DBus.ObjectManager.GetManagedObjects", 0).Store(&objs)
	if err != nil {
		return "", "", false, fmt.Errorf("BlueZ isn't running (bluetooth service): %w", err)
	}
	for p, ifs := range objs {
		if a, ok := ifs["org.bluez.Adapter1"]; ok {
			addr, _ = a["Address"].Value().(string)
			powered, _ = a["Powered"].Value().(bool)
			return p, addr, powered, nil
		}
	}
	return "", "", false, fmt.Errorf("no Bluetooth adapter")
}

func (b *bridge) serveBluetooth() (addr string, err error) {
	conn, err := dbus.ConnectSystemBus()
	if err != nil {
		return "", err
	}
	path, addr, powered, err := btAdapter(conn)
	if err != nil {
		conn.Close()
		return "", err
	}
	if !powered { // try to turn it on (works unless rfkill blocks it)
		conn.Object("org.bluez", path).Call("org.freedesktop.DBus.Properties.Set", 0,
			"org.bluez.Adapter1", "Powered", dbus.MakeVariant(true))
		if _, _, powered, _ = btAdapter(conn); !powered {
			conn.Close()
			return "", fmt.Errorf("Bluetooth is off and can't be turned on (rfkill unblock bluetooth)")
		}
	}
	mgr := conn.Object("org.bluez", "/org/bluez")
	for role, uuid := range btUUID {
		op := dbus.ObjectPath(fmt.Sprintf("/dev/gabriwar/phonebridge/role%d", role))
		if err := conn.Export(&btProfile{b: b, role: role}, op, "org.bluez.Profile1"); err != nil {
			conn.Close()
			return "", err
		}
		opts := map[string]dbus.Variant{
			"Name":                  dbus.MakeVariant("USB Audio Bridge (" + roleNames[role] + ")"),
			"Role":                  dbus.MakeVariant("server"),
			"RequireAuthentication": dbus.MakeVariant(true),
			"RequireAuthorization":  dbus.MakeVariant(false),
		}
		if c := mgr.Call("org.bluez.ProfileManager1.RegisterProfile", 0, op, uuid, opts); c.Err != nil {
			conn.Close()
			return "", fmt.Errorf("can't register the Bluetooth service: %w", c.Err)
		}
	}
	b.closers = append(b.closers, conn) // closing the bus connection unregisters the profiles
	return addr, nil
}
