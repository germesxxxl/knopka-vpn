//go:build tools

// Keeps golang.org/x/mobile/bind in go.mod after `go mod tidy`; gomobile needs it.
package core

import _ "golang.org/x/mobile/bind"
