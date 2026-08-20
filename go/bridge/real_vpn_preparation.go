package core

import (
	"sync"
	"time"
)

// realVpnPreparationOwner owns exactly one pre-TUN client preparation. The
// Android bridge serializes ownership operations with realVpnState; this type
// separately protects the stage callback because it is invoked from Go client
// setup code while cancellation may arrive from another bridge call.
type realVpnPreparationOwner struct {
	startedAt time.Time
	close     func()

	stageMu sync.RWMutex
	stage   string
	closeMu sync.Once
}

func newRealVpnPreparationOwner(closeClient func()) *realVpnPreparationOwner {
	return &realVpnPreparationOwner{
		startedAt: time.Now(),
		close:     closeClient,
		stage:     "prepare.resource",
	}
}

func (owner *realVpnPreparationOwner) setStage(stage string) {
	if owner == nil {
		return
	}
	owner.stageMu.Lock()
	owner.stage = stage
	owner.stageMu.Unlock()
}

func (owner *realVpnPreparationOwner) snapshot() (stage string, durationMillis int64) {
	if owner == nil {
		return "", 0
	}
	owner.stageMu.RLock()
	stage = owner.stage
	owner.stageMu.RUnlock()
	return stage, time.Since(owner.startedAt).Milliseconds()
}

func (owner *realVpnPreparationOwner) cancel() {
	if owner == nil {
		return
	}
	owner.closeMu.Do(func() {
		if owner.close != nil {
			owner.close()
		}
	})
}

// realVpnPreparationOwnership is protected by realVpnState's mutex. Keeping
// this comparison-only helper platform-neutral makes late-result ownership
// rules independently testable on the host bridge package.
type realVpnPreparationOwnership struct {
	current *realVpnPreparationOwner
}

func (ownership *realVpnPreparationOwnership) begin(owner *realVpnPreparationOwner) bool {
	if owner == nil || ownership.current != nil {
		return false
	}
	ownership.current = owner
	return true
}

func (ownership *realVpnPreparationOwnership) complete(owner *realVpnPreparationOwner) bool {
	if ownership.current != owner {
		return false
	}
	ownership.current = nil
	return true
}

func (ownership *realVpnPreparationOwnership) cancel() *realVpnPreparationOwner {
	owner := ownership.current
	ownership.current = nil
	return owner
}

func realVpnPreparationEvent(state, code, stage, cause string, durationMillis int64, message string) string {
	return marshal(realVpnPreparedEvent{
		SchemaVersion:  schemaVersion,
		Type:           "vpnPrepared",
		State:          state,
		Code:           code,
		Stage:          stage,
		Cause:          cause,
		DurationMillis: durationMillis,
		Message:        message,
	})
}
