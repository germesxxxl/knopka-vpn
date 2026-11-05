// Package core is a thin gomobile wrapper around the Mihomo (Clash.Meta) core.
//
// The Android app builds a Mihomo YAML config and passes the VpnService TUN fd
// separately so this package can validate the config before duplicating it.
// gomobile exposes this package to Kotlin as `core.Core`.
package core

import (
	"errors"
	"fmt"
	"io"
	"path/filepath"
	"strings"
	"sync"
	"time"

	"github.com/metacubex/mihomo/config"
	"github.com/metacubex/mihomo/constant"
	"github.com/metacubex/mihomo/hub"
	"github.com/metacubex/mihomo/hub/executor"
	"github.com/metacubex/mihomo/listener"
	LC "github.com/metacubex/mihomo/listener/config"
	"github.com/metacubex/mihomo/log"
	"github.com/metacubex/mihomo/tunnel"
	"github.com/metacubex/mihomo/tunnel/statistic"
	"go.yaml.in/yaml/v3"
	"golang.org/x/sys/unix"
)

var (
	mu      sync.Mutex
	running bool
)

// Start applies configYAML and starts the core. homeDir must contain the
// provider and rule-set files referenced by the config (relative paths).
// Calling Start while running restarts the core with the new config.
//
// Returns an error if the TUN listener did not come up: Mihomo itself only
// logs that failure, which would leave a VPN that silently drops all traffic.
func Start(homeDir string, configYAML string, tunFd int32) error {
	startLogCapture()

	mu.Lock()
	defer mu.Unlock()

	if running {
		shutdownLocked()
	}

	constant.SetHomeDir(homeDir)
	constant.SetConfig(filepath.Join(homeDir, "config.yaml"))
	// Parse before duplicating the Android descriptor. A malformed config then
	// cannot leak a detached fd. The duplicate is created here and ownership is
	// transferred to sing-tun only when the validated config is applied.
	if _, err := executor.ParseWithBytes([]byte(configYAML)); err != nil {
		return err
	}
	coreFd, err := unix.Dup(int(tunFd))
	if err != nil {
		return fmt.Errorf("duplicate TUN fd: %w", err)
	}

	// ReCreateTun skips creating a listener when the new tun config equals its
	// cached config. Reset through the listener API so the cache and listener are
	// changed together under Mihomo's own lock.
	listener.ReCreateTun(LC.Tun{}, tunnel.Tunnel)

	if err := hub.Parse([]byte(configYAML), func(cfg *config.Config) {
		cfg.General.Tun.FileDescriptor = coreFd
	}); err != nil {
		_ = unix.Close(coreFd)
		return err
	}
	running = true

	if !listener.GetTunConf().Enable {
		msg := lastLogContaining("TUN")
		shutdownLocked()
		if msg == "" {
			msg = "unknown error"
		}
		return errors.New("TUN не запустился: " + msg)
	}
	return nil
}

// Stop shuts the core down: closes the TUN listener and all live connections.
func Stop() {
	mu.Lock()
	defer mu.Unlock()
	if running {
		shutdownLocked()
	}
}

// IsRunning reports whether the core is currently started.
func IsRunning() bool {
	mu.Lock()
	defer mu.Unlock()
	return running
}

// Version returns the Mihomo core version string.
func Version() string {
	return constant.Version
}

// Logs returns the most recent core log lines (info and above), oldest first.
func Logs() string {
	logMu.Lock()
	defer logMu.Unlock()
	return strings.Join(logBuf, "\n")
}

// ValidateProvider performs a bounded structural check before a downloaded
// provider is allowed to replace the last known-good file. Mihomo performs the
// protocol-specific validation when its provider is reloaded; Android rolls
// the file back if that reload fails.
func ValidateProvider(data string) error {
	const maxProviderBytes = 2 * 1024 * 1024
	if len(data) == 0 || len(data) > maxProviderBytes {
		return fmt.Errorf("provider size is outside 1..%d bytes", maxProviderBytes)
	}

	var document struct {
		Proxies []map[string]any `yaml:"proxies"`
	}
	if err := yaml.Unmarshal([]byte(data), &document); err != nil {
		return fmt.Errorf("invalid YAML: %w", err)
	}
	if len(document.Proxies) == 0 || len(document.Proxies) > 2000 {
		return fmt.Errorf("provider must contain 1..2000 proxies")
	}

	for index, proxy := range document.Proxies {
		name, nameOK := proxy["name"].(string)
		typeName, typeOK := proxy["type"].(string)
		name = strings.TrimSpace(name)
		typeName = strings.TrimSpace(typeName)
		if !nameOK || !typeOK || name == "" || typeName == "" {
			return fmt.Errorf("proxy %d has no name or type", index+1)
		}
	}
	return nil
}

func shutdownLocked() {
	statistic.DefaultManager.Range(func(c statistic.Tracker) bool {
		_ = c.Close()
		return true
	})

	// Provider health checks own background goroutines. Close them explicitly;
	// executor.Shutdown only closes TUN in Mihomo v1.19.33.
	for _, provider := range tunnel.Providers() {
		if closer, ok := any(provider).(io.Closer); ok {
			_ = closer.Close()
		}
	}
	for _, provider := range tunnel.RuleProviders() {
		if closer, ok := any(provider).(io.Closer); ok {
			_ = closer.Close()
		}
	}

	// Applying an empty runtime config through executor is synchronous and shuts
	// down mixed/http/socks ports, DNS and TUN without racing the asynchronous
	// external-controller restart performed by hub.Parse.
	if cfg, err := executor.ParseWithBytes([]byte("mode: direct\n")); err == nil {
		executor.ApplyConfig(cfg, true)
	} else {
		// This config is constant, but keep a safe fallback for future core changes.
		listener.ReCreateTun(LC.Tun{}, tunnel.Tunnel)
	}
	executor.Shutdown()
	running = false
}

// ---- log capture for the in-app diagnostics report ----

const maxLogLines = 400

var (
	logMu   sync.Mutex
	logBuf  []string
	logOnce sync.Once
)

func startLogCapture() {
	logOnce.Do(func() {
		sub := log.Subscribe()
		go func() {
			for ev := range sub {
				if ev.LogLevel == log.DEBUG {
					continue
				}
				line := time.Now().Format("15:04:05") + " " + ev.LogLevel.String() + " " + ev.Payload
				logMu.Lock()
				logBuf = append(logBuf, line)
				if len(logBuf) > maxLogLines {
					logBuf = logBuf[len(logBuf)-maxLogLines:]
				}
				logMu.Unlock()
			}
		}()
	})
}

// lastLogContaining waits briefly for async log delivery, then returns the
// newest captured line containing substr.
func lastLogContaining(substr string) string {
	time.Sleep(300 * time.Millisecond)
	logMu.Lock()
	defer logMu.Unlock()
	for i := len(logBuf) - 1; i >= 0; i-- {
		if strings.Contains(logBuf[i], substr) {
			return logBuf[i]
		}
	}
	return ""
}
