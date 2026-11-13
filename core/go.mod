module butone/core

go 1.26

require github.com/metacubex/mihomo v1.19.34

// Mirrors the replace directive in Mihomo's own go.mod; replace directives
// are not inherited by dependents, so the build fails without it.
replace google.golang.org/protobuf => github.com/metacubex/protobuf-go v0.0.0-20260306035419-7ceee0674686
