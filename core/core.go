// Package core is a thin gomobile wrapper around the Mihomo (Clash.Meta) core.
//
// The Android app builds a complete Mihomo YAML config (including
// `tun.file-descriptor` pointing at the fd from VpnService) and passes it here.
// gomobile exposes this package to Kotlin as `core.Core`.
package core

import (
	"path/filepath"
	"sync"

	"github.com/metacubex/mihomo/constant"
	"github.com/metacubex/mihomo/hub"
	"github.com/metacubex/mihomo/hub/executor"
	"github.com/metacubex/mihomo/tunnel/statistic"
)

var (
	mu      sync.Mutex
	running bool
)

// Start applies configYAML and starts the core. homeDir must contain the
// provider and rule-set files referenced by the config (relative paths).
// Calling Start while running restarts the core with the new config.
func Start(homeDir string, configYAML string) error {
	mu.Lock()
	defer mu.Unlock()

	if running {
		shutdownLocked()
	}

	constant.SetHomeDir(homeDir)
	constant.SetConfig(filepath.Join(homeDir, "config.yaml"))

	if err := hub.Parse([]byte(configYAML)); err != nil {
		return err
	}
	running = true
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

func shutdownLocked() {
	executor.Shutdown()
	statistic.DefaultManager.Range(func(c statistic.Tracker) bool {
		_ = c.Close()
		return true
	})
	running = false
}
