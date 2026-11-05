// Package core is a thin gomobile wrapper around the Mihomo (Clash.Meta) core.
//
// The Android app builds a complete Mihomo YAML config (including
// `tun.file-descriptor` pointing at the fd from VpnService) and passes it here.
// gomobile exposes this package to Kotlin as `core.Core`.
package core

import (
	"errors"
	"path/filepath"
	"strings"
	"sync"
	"time"

	"github.com/metacubex/mihomo/constant"
	"github.com/metacubex/mihomo/hub"
	"github.com/metacubex/mihomo/hub/executor"
	"github.com/metacubex/mihomo/listener"
	LC "github.com/metacubex/mihomo/listener/config"
	"github.com/metacubex/mihomo/log"
	"github.com/metacubex/mihomo/tunnel/statistic"
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
func Start(homeDir string, configYAML string) error {
	startLogCapture()

	mu.Lock()
	defer mu.Unlock()

	if running {
		shutdownLocked()
	}

	constant.SetHomeDir(homeDir)
	constant.SetConfig(filepath.Join(homeDir, "config.yaml"))

	// ReCreateTun skips creating a listener when the new tun config equals
	// LastTunConf, and Shutdown does not reset it. A reconnect usually gets the
	// same fd number back, so without this the second connect has no TUN.
	listener.LastTunConf = LC.Tun{}

	if err := hub.Parse([]byte(configYAML)); err != nil {
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

func shutdownLocked() {
	executor.Shutdown()
	statistic.DefaultManager.Range(func(c statistic.Tracker) bool {
		_ = c.Close()
		return true
	})
	listener.LastTunConf = LC.Tun{}
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
